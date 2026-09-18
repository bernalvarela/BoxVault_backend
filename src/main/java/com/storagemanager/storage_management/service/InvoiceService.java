package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.config.InvoicingProperties;
import com.storagemanager.storage_management.config.VatUtils;
import com.storagemanager.storage_management.dto.InvoiceDTO;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.Client;
import com.storagemanager.storage_management.model.Document;
import com.storagemanager.storage_management.model.Invoice;
import com.storagemanager.storage_management.model.Payment;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.model.enums.DocumentType;
import com.storagemanager.storage_management.model.enums.InvoiceType;
import com.storagemanager.storage_management.repository.InvoiceRepository;
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
import java.util.List;

/**
 * Las facturas de las mensualidades: emitirlas, volver a imprimirlas y
 * rectificarlas.
 * <p>
 * Tres reglas sostienen todo lo demás:
 * <ol>
 *   <li><b>Una factura se congela al emitirse.</b> Sus cifras y su destinatario
 *       quedan guardados en {@link Invoice}; el PDF se compone a partir de ahí y
 *       no del cobro, de modo que cambiar la mensualidad no reescribe lo que ya
 *       se entregó.</li>
 *   <li><b>Un número nunca se reutiliza ni se salta.</b> No hay borrado: la serie
 *       tiene que ser correlativa, y un hueco es lo primero que se mira en una
 *       inspección.</li>
 *   <li><b>Lo que está mal se corrige con otra factura.</b> Si cambia lo
 *       facturado —el importe, el inquilino— o el mes se anula, se emite una
 *       rectificativa (artículo 15 del Reglamento de facturación): serie aparte,
 *       referencia a la rectificada y la causa. La original se queda.</li>
 * </ol>
 * Volver a componer el papel ({@link #regenerate}) no es ninguna de esas cosas:
 * son las mismas cifras impresas otra vez, para cuando lo que falló fue la
 * impresión y no la operación.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final RentalDocumentService rentalDocuments;
    private final DocumentService documents;
    private final InvoicePdfService pdf;
    private final InvoiceIssuer issuers;
    private final InvoicingProperties properties;
    private final UnitScope unitScope;

    // ------------------------------------------------------------------
    // Emitir
    // ------------------------------------------------------------------

    /**
     * Emite la factura de la mensualidad, o devuelve la que ya está en vigor. El
     * PDF queda archivado como documento del alquiler, junto al contrato.
     */
    @Transactional
    public Invoice issue(Long paymentId) {
        Payment payment = accessiblePayment(paymentId);

        Invoice current = inForce(paymentId);
        if (current != null) return current;

        if (payment.getRentalAgreement() == null) {
            throw new BadRequestException("El cobro " + paymentId + " no está asociado a ningún contrato");
        }
        StorageUnit unit = payment.getStorageUnit();
        if (unit != null && !unit.isVatApplicable()) {
            throw new BadRequestException("El alquiler de " + unit.getName()
                    + " está exento de IVA (artículo 20.Uno.23º de la Ley 37/1992): no se emite factura");
        }
        return emit(payment, InvoiceType.ORDINARIA, null, null);
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
        if (payment.getInvoiceNumber() != null) return;
        // Un mes marcado como no cobrable, o todavía sin cobrar, no tiene
        // operación que facturar.
        if (paid.signum() <= 0) return;

        try {
            emit(payment, InvoiceType.ORDINARIA, null, null);
        } catch (RuntimeException e) {
            log.error("No se pudo emitir la factura del cobro {} ({}); queda por emitir a mano",
                    paymentId, e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // Rectificar
    // ------------------------------------------------------------------

    /**
     * Emite una factura rectificativa de la que está en vigor: número de la serie
     * rectificativa, las cifras de hoy en sustitución de las de entonces, y la
     * causa, que es obligatoria.
     * <p>
     * Se emite a petición y no sola al cambiar la mensualidad, aunque sea ahí
     * cuando hace falta: corregir una errata dos minutos después de emitir
     * quemaría un número de la serie rectificativa para siempre. La aplicación
     * avisa de que el cobro ya no cuadra con su factura
     * ({@code Payment.invoiceOutdated}) y quien lleva la casa decide.
     */
    @Transactional
    public Invoice rectify(Long paymentId, String reason) {
        Payment payment = accessiblePayment(paymentId);
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("Una factura rectificativa tiene que declarar la causa de la rectificación");
        }
        Invoice current = inForce(paymentId);
        if (current == null) {
            throw new BadRequestException("El cobro " + paymentId + " no tiene ninguna factura que rectificar");
        }
        return emit(payment, InvoiceType.RECTIFICATIVA, current, reason.trim());
    }

    // ------------------------------------------------------------------
    // Volver a imprimir
    // ------------------------------------------------------------------

    /**
     * Vuelve a componer el PDF de la factura en vigor, con su mismo número, su
     * misma fecha y <b>sus mismas cifras</b>, y sustituye el archivado.
     * <p>
     * Esto no emite nada: es la misma factura, impresa otra vez. Sirve para
     * cuando lo que estaba mal era el papel —faltaba el NIF del emisor, el mes
     * salía mal escrito—, y por eso el PDF anterior se borra: no era más que un
     * dibujo de los mismos datos. Lo que no puede hacer es cambiar lo facturado:
     * para eso está {@link #rectify}.
     */
    @Transactional
    public Invoice regenerate(Long paymentId) {
        accessiblePayment(paymentId);
        Invoice invoice = inForce(paymentId);
        if (invoice == null) {
            throw new BadRequestException("El cobro " + paymentId + " no tiene ninguna factura que rehacer");
        }
        Document previous = invoice.getDocument();
        Document document = compose(invoice);
        invoice.setDocument(document);
        invoiceRepository.save(invoice);
        syncPayment(invoice);
        if (previous != null) {
            rentalDocuments.delete(invoice.getPayment().getRentalAgreement().getId(), previous.getId());
        }

        log.info("Rehecho el PDF de la factura {}: mismo número, misma fecha y mismas cifras", invoice.getNumber());
        return invoice;
    }

    // ------------------------------------------------------------------
    // Consultar
    // ------------------------------------------------------------------

    /** Todas las facturas de una mensualidad, la última primero. */
    public List<InvoiceDTO> history(Long paymentId) {
        accessiblePayment(paymentId);
        return invoiceRepository.findByPaymentIdOrderByIssuedOnDescIdDesc(paymentId).stream()
                .map(InvoiceDTO::of)
                .toList();
    }

    /** El PDF de la factura en vigor, para verlo o descargarlo. */
    public DocumentService.Content open(Long paymentId) {
        accessiblePayment(paymentId);
        Invoice invoice = inForce(paymentId);
        if (invoice == null || invoice.getDocument() == null) {
            throw new BadRequestException("El cobro " + paymentId + " todavía no tiene factura emitida");
        }
        return documents.open(invoice.getDocument());
    }

    // ------------------------------------------------------------------
    // Lo de dentro
    // ------------------------------------------------------------------

    /**
     * La factura que manda ahora mismo: la última emitida. Si hay
     * rectificativas, la última de ellas; si no, la ordinaria.
     */
    private Invoice inForce(Long paymentId) {
        List<Invoice> all = invoiceRepository.findByPaymentIdOrderByIssuedOnDescIdDesc(paymentId);
        return all.isEmpty() ? null : all.get(0);
    }

    /** Emite: congela las cifras, compone el PDF y lo archiva. */
    private Invoice emit(Payment payment, InvoiceType type, Invoice rectifies, String reason) {
        StorageUnit unit = payment.getStorageUnit();
        boolean vatApplicable = unit == null || unit.isVatApplicable();
        VatUtils.Breakdown amounts = VatUtils.breakdown(payment.getAmountDue(), vatApplicable);
        Client client = payment.getClient();

        Invoice invoice = invoiceRepository.save(Invoice.builder()
                .payment(payment)
                .number(nextNumber(type))
                .type(type)
                .rectifies(rectifies)
                .reason(reason)
                .issuedOn(LocalDate.now())
                .base(amounts.base())
                .vat(amounts.vat())
                .total(amounts.total())
                .vatRate(vatApplicable ? new BigDecimal("21.00") : BigDecimal.ZERO)
                .clientName(client != null ? client.getFullName() : null)
                .clientTaxId(client != null ? client.getDocumentId() : null)
                .build());

        invoice.setDocument(compose(invoice));
        invoiceRepository.save(invoice);
        syncPayment(invoice);

        log.info("Emitida la factura {} ({}) del cobro {}: {}",
                invoice.getNumber(), type, payment.getId(), Pdfs.euros(invoice.getTotal()));
        return invoice;
    }

    /** Compone el PDF de una factura y lo archiva en el alquiler. */
    private Document compose(Invoice invoice) {
        Payment payment = invoice.getPayment();
        byte[] content = pdf.render(invoice, issuers.forUnit(payment.getStorageUnit()));
        String period = Pdfs.monthOf(payment.getBillingPeriodYear(), payment.getBillingPeriodMonth());
        String kind = invoice.isRectificativa() ? "Factura rectificativa " : "Factura ";
        return rentalDocuments.attach(
                payment.getRentalAgreement().getId(),
                fileName(invoice),
                "application/pdf",
                content,
                DocumentType.FACTURA,
                kind + invoice.getNumber() + " · " + period);
    }

    /**
     * El nombre del fichero: {@code factura-A2026-0001-trastero-3.pdf}.
     * <p>
     * Acaba en el disco de alguien, así que tiene que decir qué es sin abrirlo:
     * el número de la factura y de qué unidad es. Las barras del número no valen
     * en un nombre de fichero, así que pasan a guiones.
     */
    private String fileName(Invoice invoice) {
        StorageUnit unit = invoice.getPayment().getStorageUnit();
        String what = invoice.isRectificativa() ? "factura-rectificativa-" : "factura-";
        String where = unit == null ? "" : Pdfs.slug(
                unit.getName() != null && !unit.getName().isBlank() ? unit.getName() : unit.getUnitNumber());
        return what + invoice.getNumber().replace('/', '-')
               + (where.isEmpty() ? "" : "-" + where) + ".pdf";
    }

    /**
     * Deja en el cobro cuál es su factura en vigor. Es una copia, sí, pero
     * evita una consulta por renglón en el registro de mensualidades, que es la
     * lista más larga de la aplicación; y {@code invoicedTotal} es lo que permite
     * ver de un vistazo que lo facturado ya no cuadra con el cobro.
     */
    private void syncPayment(Invoice invoice) {
        Payment payment = invoice.getPayment();
        payment.setInvoiceNumber(invoice.getNumber());
        payment.setInvoicedAt(invoice.getIssuedOn());
        payment.setInvoicedTotal(invoice.getTotal());
        payment.setInvoiceDocument(invoice.getDocument());
        paymentRepository.save(payment);
    }

    /**
     * El siguiente de la serie del año: {@code A2026/0001} para las ordinarias y
     * {@code R2026/0001} para las rectificativas, que van en serie aparte.
     * Correlativo y sin huecos, que es lo que pide el reglamento de facturación.
     */
    private String nextNumber(InvoiceType type) {
        String series = type == InvoiceType.RECTIFICATIVA
                ? properties.getRectificativeSeries()
                : properties.getInvoiceSeries();
        String prefix = series + LocalDate.now().getYear() + "/";
        int ordinal = invoiceRepository.lastNumber(prefix)
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

    private Payment accessiblePayment(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found with id: " + paymentId));
        unitScope.requireAccessible(payment.getStorageUnit());
        return payment;
    }
}
