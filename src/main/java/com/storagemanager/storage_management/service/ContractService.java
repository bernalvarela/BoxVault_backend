package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.model.Document;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.enums.DocumentType;
import com.storagemanager.storage_management.repository.RentalAgreementRepository;
import com.storagemanager.storage_management.service.pdf.ContractPdfService;
import com.storagemanager.storage_management.service.pdf.Pdfs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

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
        byte[] content = compose(rental);

        Document previous = rental.getContractDocument();
        Document document = rentalDocuments.attach(rentalId, fileNameOf(rental), "application/pdf", content,
                DocumentType.CONTRATO_ALQUILER,
                "Contrato generado el " + Pdfs.day(LocalDate.now()) + " (sin firmar)");
        rental.setContractDocument(document);
        rentalRepository.save(rental);

        // Después de apuntar el nuevo, no antes: si esto falla, lo que queda es
        // un PDF de más, no un alquiler apuntando a un documento que ya no está.
        if (previous != null && !previous.getId().equals(document.getId())) {
            rentalDocuments.delete(rentalId, previous.getId());
            log.info("Rehecho el contrato del alquiler {}: se retira el borrador anterior ({})",
                    rentalId, previous.getFileName());
        }

        log.info("Generado el contrato del alquiler {} ({})", rentalId, rental.getAgreementNumber());
        return document;
    }

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
