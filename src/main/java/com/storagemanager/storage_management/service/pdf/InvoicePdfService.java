package com.storagemanager.storage_management.service.pdf;

import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Element;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import com.storagemanager.storage_management.service.InvoiceIssuer;
import com.storagemanager.storage_management.config.VatUtils;
import com.storagemanager.storage_management.model.Client;
import com.storagemanager.storage_management.model.Invoice;
import com.storagemanager.storage_management.model.Payment;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.model.enums.PaymentMethod;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;

import static com.storagemanager.storage_management.config.InvoicingProperties.orMissing;

/**
 * La factura de una mensualidad, en PDF.
 * <p>
 * Los trasteros llevan el 21 % de IVA, así que un inquilino que sea empresa
 * necesita una factura en regla para deducírselo: con el número de serie, la
 * fecha, los datos fiscales de las dos partes y la base y la cuota separadas.
 * Eso es justo lo que lleva este documento; el desglose no se calcula aquí, sale
 * de {@link VatUtils}, el mismo que usan el 303 y los informes.
 * <p>
 * Las viviendas están exentas ({@code vatApplicable == false}): entonces no hay
 * cuota que repercutir y el documento lo dice, citando el artículo, en vez de
 * imprimir un 0 % que no explica nada.
 */
@Service
public class InvoicePdfService {

    /**
     * Composición de una factura ya emitida. Las cifras salen de ella y no del
     * cobro: una factura dice lo que decía el día que se emitió, aunque la
     * mensualidad haya cambiado después. Para eso están congeladas.
     */
    public byte[] render(Invoice invoice, InvoiceIssuer.Issuer issuer) {
        Payment payment = invoice.getPayment();
        String invoiceNumber = invoice.getNumber();
        LocalDate issuedOn = invoice.getIssuedOn();
        RentalAgreement rental = payment.getRentalAgreement();
        StorageUnit unit = payment.getStorageUnit();

        boolean vatApplicable = invoice.getVatRate() != null && invoice.getVatRate().signum() > 0;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document pdf = new Document(PageSize.A4, 48, 48, 48, 48);
        try {
            PdfWriter.getInstance(pdf, out);
            pdf.addTitle("Factura " + invoiceNumber);
            pdf.addCreator("BoxVault");
            pdf.open();

            pdf.add(header(invoice, issuer));
            pdf.add(Pdfs.gap(18));
            pdf.add(parties(invoice));
            if (invoice.isRectificativa()) {
                pdf.add(Pdfs.gap(12));
                pdf.add(rectification(invoice));
            }
            pdf.add(Pdfs.gap(18));
            pdf.add(lines(invoice, payment, unit, rental, vatApplicable));
            pdf.add(totals(invoice, vatApplicable));
            pdf.add(Pdfs.gap(18));
            pdf.add(collection(payment, issuer));
            pdf.add(Pdfs.gap(24));
            pdf.add(footer(vatApplicable, issuer));
        } catch (DocumentException e) {
            throw new IllegalStateException("No se pudo componer la factura " + invoiceNumber, e);
        } finally {
            if (pdf.isOpen()) pdf.close();
        }
        return out.toByteArray();
    }

    /** Emisor a la izquierda, identificación de la factura a la derecha. */
    private PdfPTable header(Invoice invoice, InvoiceIssuer.Issuer issuer) {
        Payment payment = invoice.getPayment();
        PdfPTable table = new PdfPTable(new float[]{3, 2});
        table.setWidthPercentage(100);

        Paragraph emitter = new Paragraph();
        emitter.add(new Phrase(orMissing(issuer.name()) + "\n", Pdfs.H2));
        emitter.add(new Phrase("NIF " + orMissing(issuer.taxId()) + "\n", Pdfs.BODY));
        emitter.add(new Phrase(orMissing(issuer.address()) + "\n", Pdfs.BODY));
        // El municipio sólo si se lleva aparte: el domicilio del propietario ya
        // suele traerlo, y una línea de puntos de más no ayuda a nadie.
        if (issuer.city() != null && !issuer.city().isBlank()) {
            emitter.add(new Phrase(issuer.city().trim() + "\n", Pdfs.BODY));
        }
        String contact = contactLine(issuer);
        if (!contact.isEmpty()) emitter.add(new Phrase(contact, Pdfs.SMALL));

        Paragraph identification = new Paragraph();
        identification.add(new Phrase(
                invoice.isRectificativa() ? "FACTURA\nRECTIFICATIVA\n" : "FACTURA\n", Pdfs.TITLE));
        identification.add(new Phrase("Nº " + invoice.getNumber() + "\n", Pdfs.BODY_BOLD));
        identification.add(new Phrase("Fecha de expedición: " + Pdfs.day(invoice.getIssuedOn()) + "\n", Pdfs.BODY));
        identification.add(new Phrase("Periodo: "
                + Pdfs.monthOf(payment.getBillingPeriodYear(), payment.getBillingPeriodMonth()), Pdfs.BODY));
        identification.setAlignment(Element.ALIGN_RIGHT);

        table.addCell(Pdfs.plain(emitter));
        table.addCell(Pdfs.plain(identification));
        return table;
    }

    private String contactLine(InvoiceIssuer.Issuer issuer) {
        StringBuilder line = new StringBuilder();
        if (issuer.email() != null && !issuer.email().isBlank()) {
            line.append(issuer.email().trim());
        }
        if (issuer.phone() != null && !issuer.phone().isBlank()) {
            if (line.length() > 0) line.append(" · ");
            line.append(issuer.phone().trim());
        }
        return line.toString();
    }

    /** A quién se factura, tal como se facturó. */
    private PdfPTable parties(Invoice invoice) {
        Client client = invoice.getPayment().getClient();
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);

        Paragraph to = new Paragraph();
        to.add(new Phrase("FACTURAR A\n", Pdfs.LABEL));
        // El nombre y el NIF salen de la factura, no de la ficha del cliente: si
        // mañana se corrige su NIF, lo que se entregó sigue diciendo lo que decía.
        // El domicilio sí es el de hoy: no identifica la operación.
        to.add(new Phrase(orMissing(invoice.getClientName()) + "\n", Pdfs.BODY_BOLD));
        to.add(new Phrase("NIF " + orMissing(invoice.getClientTaxId()) + "\n", Pdfs.BODY));
        if (client != null && client.getAddress() != null && !client.getAddress().isBlank()) {
            to.add(new Phrase(client.getAddress(), Pdfs.BODY));
        }
        PdfPCell cell = Pdfs.plain(to);
        cell.setBackgroundColor(Pdfs.BAND);
        cell.setPadding(10);
        table.addCell(cell);
        return table;
    }

    /** El concepto facturado: una sola línea, la mensualidad. */
    private PdfPTable lines(Invoice invoice, Payment payment, StorageUnit unit, RentalAgreement rental,
                            boolean vatApplicable) {
        PdfPTable table = new PdfPTable(new float[]{6, 2, 1.4f, 2, 2});
        table.setWidthPercentage(100);

        table.addCell(Pdfs.head("Concepto", Element.ALIGN_LEFT));
        table.addCell(Pdfs.head("Base imponible", Element.ALIGN_RIGHT));
        table.addCell(Pdfs.head("IVA", Element.ALIGN_RIGHT));
        table.addCell(Pdfs.head("Cuota", Element.ALIGN_RIGHT));
        table.addCell(Pdfs.head("Importe", Element.ALIGN_RIGHT));

        String period = Pdfs.monthOf(payment.getBillingPeriodYear(), payment.getBillingPeriodMonth());
        String what = (unit == null ? "Alquiler" : "Alquiler de " + describe(unit))
                + " — " + period.toLowerCase(Pdfs.ES);
        if (rental != null && rental.getAgreementNumber() != null) {
            what += "\nContrato " + rental.getAgreementNumber();
        }

        table.addCell(Pdfs.cell(what, Pdfs.BODY, Element.ALIGN_LEFT));
        table.addCell(Pdfs.cell(Pdfs.euros(invoice.getBase()), Pdfs.BODY, Element.ALIGN_RIGHT));
        table.addCell(Pdfs.cell(vatApplicable ? rate(invoice) : "Exento", Pdfs.BODY, Element.ALIGN_RIGHT));
        table.addCell(Pdfs.cell(vatApplicable ? Pdfs.euros(invoice.getVat()) : "—", Pdfs.BODY, Element.ALIGN_RIGHT));
        table.addCell(Pdfs.cell(Pdfs.euros(invoice.getTotal()), Pdfs.BODY, Element.ALIGN_RIGHT));
        return table;
    }

    /** El tipo con el que se emitió: "21 %". Va guardado, no supuesto, por si cambia. */
    private String rate(Invoice invoice) {
        return invoice.getVatRate().stripTrailingZeros().toPlainString() + " %";
    }

    /**
     * Qué factura se rectifica y por qué. Sin esto una rectificativa no vale: el
     * artículo 15 del Reglamento de facturación exige identificar la rectificada
     * y declarar la causa.
     */
    private Paragraph rectification(Invoice invoice) {
        Invoice original = invoice.getRectifies();
        Paragraph paragraph = new Paragraph();
        paragraph.add(new Phrase("RECTIFICACIÓN\n", Pdfs.LABEL));
        if (original != null) {
            paragraph.add(new Phrase("Rectifica a la factura " + original.getNumber()
                    + ", de fecha " + Pdfs.day(original.getIssuedOn())
                    + ", por importe de " + Pdfs.euros(original.getTotal()) + ".\n", Pdfs.BODY));
        }
        if (invoice.getReason() != null && !invoice.getReason().isBlank()) {
            paragraph.add(new Phrase("Causa: " + invoice.getReason().trim() + "\n", Pdfs.BODY));
        }
        paragraph.add(new Phrase(
                "Los importes de esta factura sustituyen a los de la factura rectificada.", Pdfs.SMALL));
        return paragraph;
    }

    /** "trastero 3 (Trastero 3)" queda tonto: se dice el número y, si aporta, el nombre. */
    private String describe(StorageUnit unit) {
        String kind = switch (unit.getKind()) {
            case APARTMENT -> "la vivienda";
            case PREMISES -> "el local";
            default -> "el trastero";
        };
        String name = unit.getName() == null ? "" : unit.getName().trim();
        String number = unit.getUnitNumber() == null ? "" : unit.getUnitNumber().trim();
        if (name.equalsIgnoreCase("trastero " + number) || name.isEmpty()) {
            return kind + " nº " + number;
        }
        return kind + " nº " + number + " (" + name + ")";
    }

    /** Los totales, alineados a la derecha bajo la tabla. */
    private PdfPTable totals(Invoice invoice, boolean vatApplicable) {
        PdfPTable table = new PdfPTable(new float[]{6, 4});
        table.setWidthPercentage(100);
        table.setSpacingBefore(10);

        table.addCell(Pdfs.plain(new Paragraph("")));

        PdfPTable box = new PdfPTable(new float[]{3, 2});
        box.setWidthPercentage(100);
        box.addCell(amountLabel("Base imponible"));
        box.addCell(amountValue(Pdfs.euros(invoice.getBase()), Pdfs.BODY));
        if (vatApplicable) {
            box.addCell(amountLabel("IVA " + rate(invoice)));
            box.addCell(amountValue(Pdfs.euros(invoice.getVat()), Pdfs.BODY));
        }
        box.addCell(amountLabel("TOTAL FACTURA"));
        box.addCell(amountValue(Pdfs.euros(invoice.getTotal()), Pdfs.TOTAL));

        table.addCell(Pdfs.plain(box));
        return table;
    }

    private PdfPCell amountLabel(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, Pdfs.BODY));
        cell.setBorder(org.openpdf.text.Rectangle.NO_BORDER);
        cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        cell.setPadding(4);
        return cell;
    }

    private PdfPCell amountValue(String text, org.openpdf.text.Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBorder(org.openpdf.text.Rectangle.NO_BORDER);
        cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        cell.setPadding(4);
        return cell;
    }

    /** Cómo y cuándo se cobró; si aún no se ha cobrado, cómo hay que pagarlo. */
    private Paragraph collection(Payment payment, InvoiceIssuer.Issuer issuer) {
        BigDecimal paid = payment.getAmountPaid() == null ? BigDecimal.ZERO : payment.getAmountPaid();
        Paragraph paragraph = new Paragraph();
        paragraph.add(new Phrase("FORMA DE COBRO\n", Pdfs.LABEL));

        if (paid.signum() > 0 && payment.getPaymentDate() != null) {
            paragraph.add(new Phrase("Cobrado el " + Pdfs.day(payment.getPaymentDate())
                    + " por " + Pdfs.euros(paid) + method(payment.getPaymentMethod()) + ".", Pdfs.BODY));
        } else {
            paragraph.add(new Phrase("Pendiente de cobro. Vencimiento: "
                    + Pdfs.day(payment.getDueDate()) + ".", Pdfs.BODY));
        }
        if (issuer.iban() != null && !issuer.iban().isBlank()) {
            paragraph.add(new Phrase("\nCuenta: " + issuer.iban().trim(), Pdfs.BODY));
        }
        if (payment.getTransactionReference() != null && !payment.getTransactionReference().isBlank()) {
            paragraph.add(new Phrase("\nReferencia: " + payment.getTransactionReference().trim(), Pdfs.SMALL));
        }
        return paragraph;
    }

    private String method(PaymentMethod method) {
        if (method == null) return "";
        return switch (method) {
            case DIRECT_DEBIT -> " por domiciliación bancaria";
            case BANK_TRANSFER -> " por transferencia";
            case CASH -> " en efectivo";
            case CREDIT_CARD -> " con tarjeta";
            case STRIPE -> " por Stripe";
            case OTHER -> "";
        };
    }

    /** El pie: la exención cuando toca, y de dónde sale el documento. */
    private Paragraph footer(boolean vatApplicable, InvoiceIssuer.Issuer issuer) {
        Paragraph paragraph = new Paragraph();
        if (!vatApplicable) {
            paragraph.add(new Phrase("Operación exenta del Impuesto sobre el Valor Añadido, "
                    + "artículo 20.Uno.23º de la Ley 37/1992 (arrendamiento de vivienda).\n", Pdfs.SMALL));
        }
        // "Entidad en régimen de atribución de rentas" sólo cuando lo es: una
        // comunidad de bienes. Si quien factura es una persona, sobra.
        paragraph.add(new Phrase("Factura expedida por " + orMissing(issuer.name())
                + (issuer.entity() ? ", entidad en régimen de atribución de rentas" : "")
                + ". Conserve esta factura a efectos fiscales.", Pdfs.SMALL));
        return paragraph;
    }
}
