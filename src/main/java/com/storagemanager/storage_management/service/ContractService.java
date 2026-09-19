package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.model.Document;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.enums.DocumentType;
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
 * que no pueda cambiar: es un borrador. Se puede volver a generar tantas veces
 * como haga falta —cambió la renta, se corrigió el DNI— y cada una queda
 * archivada con su fecha. La copia firmada, que es la que vale, se sube a mano
 * cuando vuelve del inquilino.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractService {

    private final RentalAgreementService rentals;
    private final RentalDocumentService rentalDocuments;
    private final DocumentService documents;
    private final ContractPdfService pdf;
    private final InvoiceIssuer issuers;
    private final ContractTemplateService templates;
    private final TemplateImageService images;

    /** Genera el contrato y lo abre, que es lo que necesita quien lo descarga. */
    public DocumentService.Content generateAndOpen(Long rentalId) {
        return documents.open(generate(rentalId));
    }

    /** Genera el contrato del alquiler y lo archiva; devuelve el documento. */
    @Transactional
    public Document generate(Long rentalId) {
        RentalAgreement rental = rentals.getAgreementById(rentalId);
        // La plantilla que diga el contrato, la de su unidad, la del local que la
        // contiene o la de por defecto, por ese orden.
        String template = templates.textFor(rental);
        byte[] content = pdf.render(rental, issuers.forUnit(rental.getStorageUnit()), template, images::bytesOf);

        // El nombre dice qué es sin abrirlo: contrato, de qué unidad y de cuándo.
        LocalDate today = LocalDate.now();
        String unit = rental.getStorageUnit() == null ? "" : Pdfs.slug(rental.getStorageUnit().getName());
        String name = "contrato-" + slug(rental.getAgreementNumber())
                + (unit.isEmpty() ? "" : "-" + unit) + "-" + today + ".pdf";
        Document document = rentalDocuments.attach(rentalId, name, "application/pdf", content,
                DocumentType.CONTRATO_ALQUILER,
                "Contrato generado el " + Pdfs.day(today) + " (sin firmar)");

        log.info("Generado el contrato del alquiler {} ({})", rentalId, rental.getAgreementNumber());
        return document;
    }

    /** El número de contrato en el nombre del fichero, sin barras ni espacios. */
    private String slug(String agreementNumber) {
        String slug = Pdfs.slug(agreementNumber);
        return slug.isEmpty() ? "sin-numero" : slug;
    }
}
