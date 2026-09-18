package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.config.InvoicingProperties;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.model.Document;
import com.storagemanager.storage_management.model.Payment;
import com.storagemanager.storage_management.model.enums.DocumentType;
import com.storagemanager.storage_management.repository.PaymentRepository;
import com.storagemanager.storage_management.service.pdf.InvoicePdfService;
import com.storagemanager.storage_management.service.pdf.Pdfs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * La factura de una mensualidad: se emite una vez, se archiva en el alquiler y a
 * partir de ahí se entrega siempre la misma.
 * <p>
 * Los trasteros van con el 21 % de IVA, así que un inquilino que sea empresa
 * necesita la factura para deducírselo. Y una factura no es un PDF cualquiera:
 * lleva un número correlativo dentro de su serie que, una vez entregado, ya no
 * puede cambiar. Por eso el número se guarda en el cobro y el PDF queda
 * archivado: pedirla dos veces devuelve el mismo documento, no uno nuevo con
 * otro número.
 * <p>
 * Si hay que rectificar algo —el importe estaba mal, el cliente no era ese— lo
 * que procede es una factura rectificativa, no reescribir esta. Eso todavía no
 * está: de momento hay que anular el cobro y volver a emitir.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceService {

    private final PaymentService payments;
    private final PaymentRepository paymentRepository;
    private final RentalDocumentService rentalDocuments;
    private final DocumentService documents;
    private final InvoicePdfService pdf;
    private final InvoicingProperties properties;

    /** La factura entregada: el documento archivado y su contenido. */
    public record Invoice(String number, Document document) {}

    /**
     * Emite la factura del cobro, o devuelve la que ya se emitió. El PDF queda
     * archivado como documento del alquiler, junto al contrato.
     */
    @Transactional
    public Invoice issue(Long paymentId) {
        Payment payment = payments.getPaymentById(paymentId);
        if (payment.getInvoiceDocument() != null) {
            return new Invoice(payment.getInvoiceNumber(), payment.getInvoiceDocument());
        }
        if (payment.getRentalAgreement() == null) {
            throw new BadRequestException("El cobro " + paymentId + " no está asociado a ningún contrato");
        }

        LocalDate issuedOn = LocalDate.now();
        String number = payment.getInvoiceNumber() != null
                ? payment.getInvoiceNumber()
                : nextNumber(issuedOn.getYear());

        byte[] content = pdf.render(payment, number, issuedOn);
        String period = Pdfs.monthOf(payment.getBillingPeriodYear(), payment.getBillingPeriodMonth());
        Document document = rentalDocuments.attach(
                payment.getRentalAgreement().getId(),
                "factura-" + number.replace('/', '-') + ".pdf",
                "application/pdf",
                content,
                DocumentType.FACTURA,
                "Factura " + number + " · " + period);

        payment.setInvoiceNumber(number);
        payment.setInvoicedAt(issuedOn);
        payment.setInvoiceDocument(document);
        paymentRepository.save(payment);

        log.info("Emitida la factura {} del cobro {} ({})", number, paymentId, period);
        return new Invoice(number, document);
    }

    /** El PDF de una factura ya emitida, para descargarlo. */
    public DocumentService.Content open(Long paymentId) {
        Payment payment = payments.getPaymentById(paymentId);
        if (payment.getInvoiceDocument() == null) {
            throw new BadRequestException("El cobro " + paymentId + " todavía no tiene factura emitida");
        }
        return documents.open(payment.getInvoiceDocument());
    }

    /**
     * El siguiente de la serie del año: {@code A2026/0001}, {@code A2026/0002}...
     * Correlativo y sin huecos, que es lo que pide el reglamento de facturación.
     */
    private String nextNumber(int year) {
        String prefix = properties.getInvoiceSeries() + year + "/";
        int ordinal = paymentRepository.lastInvoiceNumber(prefix)
                .map(last -> ordinalOf(last, prefix))
                .orElse(0) + 1;
        return prefix + String.format("%04d", ordinal);
    }

    private int ordinalOf(String invoiceNumber, String prefix) {
        try {
            return Integer.parseInt(invoiceNumber.substring(prefix.length()));
        } catch (RuntimeException e) {
            // Un número que no sigue el formato no puede decidir cuál es el
            // siguiente; mejor parar que repetir uno ya entregado.
            throw new IllegalStateException("La última factura de la serie no tiene el formato esperado: "
                    + invoiceNumber, e);
        }
    }
}
