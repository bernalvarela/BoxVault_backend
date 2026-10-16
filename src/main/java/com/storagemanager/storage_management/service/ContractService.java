package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.dto.TerminationRequest;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.model.enums.RentalStatus;
import com.storagemanager.storage_management.model.Document;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.Owner;
import com.storagemanager.storage_management.model.enums.DocumentType;
import com.storagemanager.storage_management.model.enums.SigningKind;
import com.storagemanager.storage_management.model.enums.SigningStatus;
import com.storagemanager.storage_management.repository.ContractSigningRepository;
import com.storagemanager.storage_management.repository.RentalAgreementRepository;
import com.storagemanager.storage_management.service.pdf.ContractPdfService;
import com.storagemanager.storage_management.service.pdf.Pdfs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * El contrato de alquiler en papel: se compone desde la plantilla con los datos
 * del alquiler y se archiva como documento suyo, listo para imprimir y firmar.
 * <p>
 * A diferencia de la factura, aquí no hay número que respetar ni nada emitido
 * que no pueda cambiar: es un borrador. La copia firmada, que es la que vale,
 * se sube a mano cuando vuelve del inquilino.
 * <p>
 * Por eso se hace en dos pasos. Primero {@link #preview}, que compone el PDF y
 * lo devuelve sin archivar nada: se lee, y si algo está mal se corrige el
 * alquiler o la plantilla y se vuelve a mirar, las veces que haga falta, sin
 * dejar rastro. Sólo cuando está bien, {@link #generate} lo archiva. Y si más
 * tarde el inquilino pide un cambio, se rehace: el borrador anterior se borra
 * -el alquiler sabe cuál era- y en su sitio queda el nuevo, uno solo, que es lo
 * que evita la carpeta con cinco contratos sin saber cuál se firmó.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractService {

    private final RentalAgreementService rentals;
    private final RentalAgreementRepository rentalRepository;
    private final RentalDocumentService rentalDocuments;
    private final DocumentService documents;
    private final ContractPdfService pdf;
    private final InvoiceIssuer issuers;
    private final ContractTemplateService templates;
    private final TemplateImageService images;
    private final ContractSigningRepository signings;

    /** Un contrato compuesto que todavía no es de nadie: sólo bytes y un nombre. */
    public record Draft(String fileName, byte[] content) {}

    /**
     * Compone el contrato y lo devuelve TAL CUAL, sin archivarlo ni tocar la
     * base: el borrador para leer antes de decidir. Se puede pedir mil veces.
     */
    public Draft preview(Long rentalId) {
        RentalAgreement rental = rentals.getAgreementById(rentalId);
        return new Draft(fileNameOf(rental), compose(rental));
    }

    /** Genera el contrato y lo abre, que es lo que necesita quien lo descarga. */
    public DocumentService.Content generateAndOpen(Long rentalId) {
        return documents.open(generate(rentalId));
    }

    /**
     * Archiva el contrato del alquiler y devuelve el documento. Si ya había uno
     * generado antes, ése se borra: el bueno es el último, y dejar los dos sólo
     * sirve para firmar el que no era.
     */
    @Transactional
    public Document generate(Long rentalId) {
        RentalAgreement rental = rentals.getAgreementById(rentalId);
        requireNoSigning(rental, SigningKind.ENTRADA);
        byte[] content = compose(rental);

        Document previous = rental.getContractDocument();
        Document document = rentalDocuments.attach(rentalId, fileNameOf(rental), "application/pdf", content,
                DocumentType.CONTRATO_ALQUILER,
                "Contrato generado el " + Pdfs.day(LocalDate.now()) + " (sin firmar)");
        rental.setContractDocument(document);
        rentalRepository.save(rental);

        // Después de apuntar el nuevo, no antes: si esto falla, lo que queda es
        // un PDF de más, no un alquiler apuntando a un documento que ya no está.
        retire(rentalId, previous, document, "el borrador anterior");

        log.info("Generado el contrato del alquiler {} ({})", rentalId, rental.getAgreementNumber());
        return document;
    }

    // --- El contrato de salida ---------------------------------------------

    /**
     * Compone el contrato de salida con lo que se va a decidir al cerrar -la
     * fecha y la fianza- y lo devuelve sin cerrar nada ni archivar nada.
     * <p>
     * Los datos del cierre se ponen sobre el alquiler recién leído, que ya está
     * fuera de la sesión de Hibernate: se usan para componer y se tiran. Por eso
     * este método NO puede ser transaccional, o el cambio se guardaría.
     */
    public Draft previewExit(Long rentalId, TerminationRequest request) {
        RentalAgreement rental = rentals.getAgreementById(rentalId);
        rentals.applyTermination(rental, request);
        return new Draft(exitFileNameOf(rental), composeExit(rental));
    }

    /**
     * Cierra el contrato con lo decidido y, si se pidió, archiva su contrato de
     * salida. Todo o nada: si el PDF no se puede componer, el contrato no se
     * cierra, y así no queda un cierre sin el papel que se iba a firmar.
     */
    @Transactional
    public RentalAgreement terminate(Long rentalId, TerminationRequest request) {
        RentalAgreement rental = rentals.terminateAgreement(rentalId, request);
        if (Boolean.TRUE.equals(request.getGenerateExitContract())) {
            generateExit(rental);
        }
        return rental;
    }

    /**
     * El contrato de salida de un alquiler que YA está cerrado: no se generó al
     * cerrarlo, o hay que rehacerlo. Lo que se decidió sobre la fianza se puede
     * corregir aquí (llega en el cuerpo, como al cerrar); la fecha de salida no:
     * es la del cierre, y moverla cambiaría lo facturado.
     */
    @Transactional
    public RentalAgreement generateExitAfterwards(Long rentalId, TerminationRequest request) {
        RentalAgreement rental = rentals.getAgreementById(rentalId);
        if (rental.getStatus() == RentalStatus.ACTIVE && !rental.isTerminationScheduled()) {
            throw new BadRequestException("El contrato " + rental.getAgreementNumber()
                    + " sigue en vigor sin baja: el contrato de salida se genera al finalizarlo");
        }
        request.setTerminationDate(rental.getEndDate());
        rentals.applyTermination(rental, request);
        rentalRepository.save(rental);
        generateExit(rental);
        return rental;
    }

    /** Archiva el contrato de salida; si ya había uno generado, lo sustituye. */
    private Document generateExit(RentalAgreement rental) {
        Long rentalId = rental.getId();
        requireNoSigning(rental, SigningKind.SALIDA);
        byte[] content = composeExit(rental);

        Document previous = rental.getExitContractDocument();
        Document document = rentalDocuments.attach(rentalId, exitFileNameOf(rental), "application/pdf", content,
                DocumentType.CONTRATO_SALIDA,
                "Contrato de salida generado el " + Pdfs.day(LocalDate.now()) + " (sin firmar)");
        rental.setExitContractDocument(document);
        rentalRepository.save(rental);

        // Como en generate: primero se apunta el nuevo y luego se retira el viejo.
        retire(rentalId, previous, document, "el contrato de salida anterior");
        log.info("Generado el contrato de salida del alquiler {} ({})", rentalId, rental.getAgreementNumber());
        return document;
    }

    // --- Para firmar en BoxVault ---------------------------------------------

    /** El contrato compuesto para firmarlo, ya archivado, y dónde quedaron sus huecos de firma. */
    public record ForSigning(Document document, byte[] content, List<ContractPdfService.Slot> slots) {}

    /**
     * Compone el contrato una última vez para firmarlo en BoxVault y lo archiva
     * como el contrato del alquiler (o el de salida), en lugar del borrador que
     * hubiera. Ese PDF es el que se congela: quien llama guarda su huella.
     *
     * @param onBehalf si la única arrendadora es una comunidad de bienes, el
     *                 comunero que firma por ella; null si no
     */
    @Transactional
    public ForSigning generateForSigning(Long rentalId, SigningKind kind, Owner onBehalf) {
        RentalAgreement rental = rentals.getAgreementById(rentalId);
        requireNoSigning(rental, kind);
        boolean exit = kind == SigningKind.SALIDA;
        if (exit && rental.getStatus() == RentalStatus.ACTIVE && !rental.isTerminationScheduled()) {
            throw new BadRequestException("El contrato " + rental.getAgreementNumber()
                    + " sigue en vigor sin baja: el contrato de salida se firma al finalizarlo");
        }
        String template = exit ? templates.exitTextFor(rental) : templates.textFor(rental);
        ContractPdfService.Rendered rendered = pdf.renderForSigning(
                rental, issuers.forUnit(rental.getStorageUnit()), template, images::bytesOf, onBehalf);

        Document previous = exit ? rental.getExitContractDocument() : rental.getContractDocument();
        Document document = rentalDocuments.attach(rentalId,
                exit ? exitFileNameOf(rental) : fileNameOf(rental), "application/pdf", rendered.content(),
                exit ? DocumentType.CONTRATO_SALIDA : DocumentType.CONTRATO_ALQUILER,
                (exit ? "Contrato de salida" : "Contrato") + " preparado para firmar en BoxVault el "
                        + Pdfs.day(LocalDate.now()));
        if (exit) rental.setExitContractDocument(document);
        else rental.setContractDocument(document);
        rentalRepository.save(rental);
        retire(rentalId, previous, document, exit ? "el contrato de salida anterior" : "el borrador anterior");

        log.info("Preparado para firmar el contrato {} del alquiler {} ({} hueco(s) de firma)",
                kind, rentalId, rendered.slots().size());
        return new ForSigning(document, rendered.content(), rendered.slots());
    }

    /**
     * Mientras se firma un contrato, no se rehace: lo que unos ya han firmado
     * tiene que ser lo mismo que firmen los demás. Para cambiarlo, se cancela
     * la firma y se prepara otra.
     */
    private void requireNoSigning(RentalAgreement rental, SigningKind kind) {
        if (signings.existsByRentalAgreementIdAndKindAndStatus(rental.getId(), kind, SigningStatus.EN_CURSO)) {
            throw new BadRequestException("El " + (kind == SigningKind.SALIDA ? "contrato de salida" : "contrato")
                    + " " + rental.getAgreementNumber() + " se está firmando: cancela la firma antes de rehacerlo");
        }
    }

    /**
     * Retira el contrato generado antes, ya sustituido por el nuevo. Si ése se
     * firmó (o se intentó firmar) en BoxVault, no se borra: es la prueba de lo
     * que se firmó, y se queda en los documentos del alquiler.
     */
    private void retire(Long rentalId, Document previous, Document replacement, String what) {
        if (previous == null || previous.getId().equals(replacement.getId())) return;
        if (signings.usesDocument(previous.getId())) {
            log.info("Alquiler {}: {} ({}) se conserva porque se firmó en BoxVault", rentalId, what, previous.getFileName());
            return;
        }
        rentalDocuments.delete(rentalId, previous.getId());
        log.info("Rehecho el contrato del alquiler {}: se retira {} ({})", rentalId, what, previous.getFileName());
    }

    private byte[] composeExit(RentalAgreement rental) {
        String template = templates.exitTextFor(rental);
        return pdf.render(rental, issuers.forUnit(rental.getStorageUnit()), template, images::bytesOf);
    }

    private String exitFileNameOf(RentalAgreement rental) {
        String unit = rental.getStorageUnit() == null ? "" : Pdfs.slug(rental.getStorageUnit().getName());
        return "contrato-salida-" + slug(rental.getAgreementNumber())
                + (unit.isEmpty() ? "" : "-" + unit) + "-" + LocalDate.now() + ".pdf";
    }

    // ------------------------------------------------------------------------

    /**
     * Compone el PDF: la plantilla que diga el contrato, la de su unidad, la del
     * local que la contiene o la de por defecto, por ese orden.
     */
    private byte[] compose(RentalAgreement rental) {
        String template = templates.textFor(rental);
        return pdf.render(rental, issuers.forUnit(rental.getStorageUnit()), template, images::bytesOf);
    }

    /** El nombre dice qué es sin abrirlo: contrato, de qué unidad y de cuándo. */
    private String fileNameOf(RentalAgreement rental) {
        String unit = rental.getStorageUnit() == null ? "" : Pdfs.slug(rental.getStorageUnit().getName());
        return "contrato-" + slug(rental.getAgreementNumber())
                + (unit.isEmpty() ? "" : "-" + unit) + "-" + LocalDate.now() + ".pdf";
    }

    /** El número de contrato en el nombre del fichero, sin barras ni espacios. */
    private String slug(String agreementNumber) {
        String slug = Pdfs.slug(agreementNumber);
        return slug.isEmpty() ? "sin-numero" : slug;
    }
}
