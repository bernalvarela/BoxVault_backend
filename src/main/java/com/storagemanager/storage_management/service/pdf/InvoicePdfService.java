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
import com.storagemanager.storage_management.config.InvoicingProperties;
import com.storagemanager.storage_management.config.VatUtils;
import com.storagemanager.storage_management.model.Client;
import com.storagemanager.storage_management.model.Payment;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.model.enums.PaymentMethod;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class InvoicePdfService {

    private final InvoicingProperties issuer;

    /** Composición de la factura de un cobro. El número ya viene asignado. */
    public byte[] render(Payment payment, String invoiceNumber, LocalDate issuedOn) {
        RentalAgreement rental = payment.getRentalAgreement();
        StorageUnit unit = payment.getStorageUnit();
        Client client = payment.getClient();

        boolean vatApplicable = unit != null && unit.isVatApplicable();
        VatUtils.Breakdown amounts = VatUtils.breakdown(payment.getAmountDue(), vatApplicable);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document pdf = new Document(PageSize.A4, 48, 48, 48, 48);
        try {
            PdfWriter.getInstance(pdf, out);
            pdf.addTitle("Factura " + invoiceNumber);
            pdf.addCreator("BoxVault");
            pdf.open();

            pdf.add(header(invoiceNumber, issuedOn, payment));
            pdf.add(Pdfs.gap(18));
            pdf.add(parties(client));
            pdf.add(Pdfs.gap(18));
            pdf.add(lines(payment, unit, rental, amounts, vatApplicable));
            pdf.add(totals(amounts, vatApplicable));
            pdf.add(Pdfs.gap(18));
            pdf.add(collection(payment));
            pdf.add(Pdfs.gap(24));
            pdf.add(footer(vatApplicable));
        } catch (DocumentException e) {
            throw new IllegalStateException("No se pudo componer la factura " + invoiceNumber, e);
        } finally {
            if (pdf.isOpen()) pdf.close();
        }
        return out.toByteArray();
    }

    /** Emisor a la izquierda, identificación de la factura a la derecha. */
    private PdfPTable header(String number, LocalDate issuedOn, Payment payment) {
        PdfPTable table = new PdfPTable(new float[]{3, 2});
        table.setWidthPercentage(100);

        Paragraph emitter = new Paragraph();
        emitter.add(new Phrase(orMissing(issuer.getIssuerName()) + "\n", Pdfs.H2));
        emitter.add(new Phrase("NIF " + orMissing(issuer.getIssuerTaxId()) + "\n", Pdfs.BODY));
        emitter.add(new Phrase(orMissing(issuer.getIssuerAddress()) + "\n", Pdfs.BODY));
        emitter.add(new Phrase(orMissing(issuer.getIssuerCity()) + "\n", Pdfs.BODY));
        String contact = contactLine();
        if (!contact.isEmpty()) emitter.add(new Phrase(contact, Pdfs.SMALL));

        Paragraph identification = new Paragraph();
        identification.add(new Phrase("FACTURA\n", Pdfs.TITLE));
        identification.add(new Phrase("Nº " + number + "\n", Pdfs.BODY_BOLD));
        identification.add(new Phrase("Fecha de expedición: " + Pdfs.day(issuedOn) + "\n", Pdfs.BODY));
        identification.add(new Phrase("Periodo: "
                + Pdfs.monthOf(payment.getBillingPeriodYear(), payment.getBillingPeriodMonth()), Pdfs.BODY));
        identification.setAlignment(Element.ALIGN_RIGHT);

        table.addCell(Pdfs.plain(emitter));
        table.addCell(Pdfs.plain(identification));
        return table;
    }

    private String contactLine() {
        StringBuilder line = new StringBuilder();
        if (issuer.getIssuerEmail() != null && !issuer.getIssuerEmail().isBlank()) {
            line.append(issuer.getIssuerEmail().trim());
        }
        if (issuer.getIssuerPhone() != null && !issuer.getIssuerPhone().isBlank()) {
            if (line.length() > 0) line.append(" · ");
            line.append(issuer.getIssuerPhone().trim());
        }
        return line.toString();
    }

    /** A quién se factura. */
    private PdfPTable parties(Client client) {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);

        Paragraph to = new Paragraph();
        to.add(new Phrase("FACTURAR A\n", Pdfs.LABEL));
        to.add(new Phrase((client == null ? "—" : client.getFullName()) + "\n", Pdfs.BODY_BOLD));
        if (client != null) {
            to.add(new Phrase("NIF " + orMissing(client.getDocumentId()) + "\n", Pdfs.BODY));
            if (client.getAddress() != null && !client.getAddress().isBlank()) {
                to.add(new Phrase(client.getAddress(), Pdfs.BODY));
            }
        }
        PdfPCell cell = Pdfs.plain(to);
        cell.setBackgroundColor(Pdfs.BAND);
        cell.setPadding(10);
        table.addCell(cell);
        return table;
    }

    /** El concepto facturado: una sola línea, la mensualidad. */
    private PdfPTable lines(Payment payment, StorageUnit unit, RentalAgreement rental,
                            VatUtils.Breakdown amounts, boolean vatApplicable) {
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
        table.addCell(Pdfs.cell(Pdfs.euros(amounts.base()), Pdfs.BODY, Element.ALIGN_RIGHT));
        table.addCell(Pdfs.cell(vatApplicable ? "21 %" : "Exento", Pdfs.BODY, Element.ALIGN_RIGHT));
        table.addCell(Pdfs.cell(vatApplicable ? Pdfs.euros(amounts.vat()) : "—", Pdfs.BODY, Element.ALIGN_RIGHT));
        table.addCell(Pdfs.cell(Pdfs.euros(amounts.total()), Pdfs.BODY, Element.ALIGN_RIGHT));
        return table;
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
    private PdfPTable totals(VatUtils.Breakdown amounts, boolean vatApplicable) {
        PdfPTable table = new PdfPTable(new float[]{6, 4});
        table.setWidthPercentage(100);
        table.setSpacingBefore(10);

        table.addCell(Pdfs.plain(new Paragraph("")));

        PdfPTable box = new PdfPTable(new float[]{3, 2});
        box.setWidthPercentage(100);
        box.addCell(amountLabel("Base imponible"));
        box.addCell(amountValue(Pdfs.euros(amounts.base()), Pdfs.BODY));
        if (vatApplicable) {
            box.addCell(amountLabel("IVA 21 %"));
            box.addCell(amountValue(Pdfs.euros(amounts.vat()), Pdfs.BODY));
        }
        box.addCell(amountLabel("TOTAL FACTURA"));
        box.addCell(amountValue(Pdfs.euros(amounts.total()), Pdfs.TOTAL));

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
    private Paragraph collection(Payment payment) {
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
        if (issuer.getIssuerIban() != null && !issuer.getIssuerIban().isBlank()) {
            paragraph.add(new Phrase("\nCuenta: " + issuer.getIssuerIban().trim(), Pdfs.BODY));
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
    private Paragraph footer(boolean vatApplicable) {
        Paragraph paragraph = new Paragraph();
        if (!vatApplicable) {
            paragraph.add(new Phrase("Operación exenta del Impuesto sobre el Valor Añadido, "
                    + "artículo 20.Uno.23º de la Ley 37/1992 (arrendamiento de vivienda).\n", Pdfs.SMALL));
        }
        paragraph.add(new Phrase("Factura expedida por " + orMissing(issuer.getIssuerName())
                + ", entidad en régimen de atribución de rentas. "
                + "Documento generado por BoxVault; conserve esta factura a efectos fiscales.", Pdfs.SMALL));
        return paragraph;
    }
}
