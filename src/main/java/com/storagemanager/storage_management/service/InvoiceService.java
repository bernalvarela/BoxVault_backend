package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.config.InvoicingProperties;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.Document;
import com.storagemanager.storage_management.model.Payment;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.model.enums.DocumentType;
import com.storagemanager.storage_management.repository.PaymentRepository;
import com.storagemanager.storage_management.security.UnitScope;
import com.storagemanager.storage_management.service.pdf.InvoicePdfService;
import com.storagemanager.storage_management.service.pdf.Pdfs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * La factura de una mensualidad: se emite una vez, se archiva en el alquiler y a
 * partir de ahí se entrega siempre la misma.
 * <p>
 * Los trasteros y los locales van con el 21 % de IVA, así que un inquilino que
 * sea empresa necesita la factura para deducírselo. Las viviendas están exentas
 * ({@code vatApplicable == false}): de un alquiler de vivienda no se emite
 * factura, y pedirla es un error, no un documento con la cuota a cero.
 * <p>
 * Una factura no es un PDF cualquiera: lleva un número correlativo dentro de su
 * serie que, una vez entregado, ya no puede cambiar. Por eso el número se guarda
 * en el cobro y el PDF queda archivado: pedirla dos veces devuelve el mismo
 * documento, no uno nuevo con otro número. Y por eso tampoco se emiten "por si
 * acaso": sólo las de los contratos marcados para facturar
 * ({@code generatesInvoices}) salen solas al cobrar.
 * <p>
 * Si hay que rectificar algo —el importe estaba mal, el cliente no era ese— lo
 * que procede es una factura rectificativa, no reescribir esta. Eso todavía no
 * está: de momento hay que anular el cobro y volver a emitir.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceService {

    private final PaymentRepository paymentRepository;
    private final RentalDocumentService rentalDocuments;
    private final DocumentService documents;
    private final InvoicePdfService pdf;
    private final InvoiceIssuer issuers;
    private final InvoicingProperties properties;
    private final UnitScope unitScope;

    /** La factura entregada: su número y el documento archivado. */
    public record Invoice(String number, Document document) {}

    /**
     * Emite la factura del cobro, o devuelve la que ya se emitió. El PDF queda
     * archivado como documento del alquiler, junto al contrato.
     */
    @Transactional
    public Invoice issue(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found with id: " + paymentId));
        unitScope.requireAccessible(payment.getStorageUnit());

        if (payment.getInvoiceDocument() != null) {
            return new Invoice(payment.getInvoiceNumber(), payment.getInvoiceDocument());
        }
        if (payment.getRentalAgreement() == null) {
            throw new BadRequestException("El cobro " + paymentId + " no está asociado a ningún contrato");
        }
        StorageUnit unit = payment.getStorageUnit();
        if (unit != null && !unit.isVatApplicable()) {
            throw new BadRequestException("El alquiler de " + unit.getName()
                    + " está exento de IVA (artículo 20.Uno.23º de la Ley 37/1992): no se emite factura");
        }
        return emit(payment);
    }

    /**
     * La factura que sale sola al cobrar, cuando el contrato está marcado para
     * facturar. No emitir no es un error: es lo normal en la mayoría de los
     * contratos, así que se calla y sigue.
     * <p>
     * Y si la emisión falla —el almacén no responde, la plantilla no está— se
     * anota y se sigue también: el cobro ya está registrado y perder ese registro
     * por no haber podido escribir un PDF sería mucho peor. La factura se puede
     * pedir después a mano.
     * <p>
     * Por eso recibe el id y no el cobro, y {@link PaymentService} la llama
     * cuando su transacción ya ha confirmado: dentro de ella, un fallo aquí
     * marcaría la transacción entera para deshacerse y el cobro se perdería por
     * mucho que este método se trague la excepción.
     */
    @Transactional
    public void issueIfEnabled(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId).orElse(null);
        if (payment == null) return;

        RentalAgreement rental = payment.getRentalAgreement();
        StorageUnit unit = payment.getStorageUnit();
        BigDecimal paid = payment.getAmountPaid() == null ? BigDecimal.ZERO : payment.getAmountPaid();

        if (rental == null || !rental.invoices()) return;
        if (unit != null && !unit.isVatApplicable()) return;
        if (payment.getInvoiceDocument() != null) return;
        // Un mes marcado como no cobrable, o todavía sin cobrar, no tiene
        // operación que facturar.
        if (paid.signum() <= 0) return;

        try {
            emit(payment);
        } catch (RuntimeException e) {
            log.error("No se pudo emitir la factura del cobro {} ({}); queda por emitir a mano",
                    payment.getId(), e.getMessage(), e);
        }
    }

    /** El PDF de una factura ya emitida, para descargarlo. */
    public DocumentService.Content open(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found with id: " + paymentId));
        unitScope.requireAccessible(payment.getStorageUnit());
        if (payment.getInvoiceDocument() == null) {
            throw new BadRequestException("El cobro " + paymentId + " todavía no tiene factura emitida");
        }
        return documents.open(payment.getInvoiceDocument());
    }

    /** Compone la factura, la archiva y la deja apuntada en el cobro. */
    private Invoice emit(Payment payment) {
        LocalDate issuedOn = LocalDate.now();
        String number = payment.getInvoiceNumber() != null
                ? payment.getInvoiceNumber()
                : nextNumber(issuedOn.getYear());

        byte[] content = pdf.render(payment, number, issuedOn, issuers.forUnit(payment.getStorageUnit()));
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

        log.info("Emitida la factura {} del cobro {} ({})", number, payment.getId(), period);
        return new Invoice(number, document);
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
