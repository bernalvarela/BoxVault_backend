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
import com.storagemanager.storage_management.service.InvoiceIssuer;
import com.storagemanager.storage_management.config.VatUtils;
import com.storagemanager.storage_management.model.Client;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.StorageUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.storagemanager.storage_management.config.InvoicingProperties.orMissing;

/**
 * El contrato de alquiler en PDF, a partir de la plantilla de texto
 * ({@code plantillas/contrato-alquiler.txt}).
 * <p>
 * El texto legal vive en la plantilla y no aquí: cambiar una cláusula, añadir
 * una cuenta nueva o corregir una errata no debería obligar a recompilar nada.
 * Este servicio sólo hace dos cosas —sustituir los {@code {{campos}}} por los
 * datos del contrato y componer el resultado en un PDF— y entiende la marca
 * mínima que hace falta para que un contrato se lea: títulos, párrafos,
 * viñetas, negritas y el pie de firmas.
 * <p>
 * Un campo que no exista se deja tal cual ({@code {{loquesea}}}): así una errata
 * en la plantilla se ve en el papel en lugar de convertirse en un hueco que
 * nadie nota.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractPdfService {

    private static final Pattern FIELD = Pattern.compile("\\{\\{([a-z_]+)}}");

    private final InvoicingProperties properties;

    public byte[] render(RentalAgreement rental, InvoiceIssuer.Issuer issuer) {
        String template = loadTemplate();
        Map<String, String> values = values(rental, issuer);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document pdf = new Document(PageSize.A4, 56, 56, 56, 56);
        try {
            PdfWriter.getInstance(pdf, out);
            pdf.addTitle("Contrato de arrendamiento " + rental.getAgreementNumber());
            pdf.addCreator("BoxVault");
            pdf.open();
            for (String block : blocks(template)) {
                pdf.add(compose(fill(block, values)));
            }
        } catch (DocumentException e) {
            throw new IllegalStateException(
                    "No se pudo componer el contrato " + rental.getAgreementNumber(), e);
        } finally {
            if (pdf.isOpen()) pdf.close();
        }
        return out.toByteArray();
    }

    // --- La plantilla ------------------------------------------------------

    private String loadTemplate() {
        ClassPathResource resource = new ClassPathResource(properties.getContractTemplate());
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "No se pudo leer la plantilla del contrato: " + properties.getContractTemplate(), e);
        }
    }

    /**
     * Parte la plantilla en bloques: los comentarios se caen, una línea en
     * blanco separa un bloque del siguiente y las líneas de un mismo bloque se
     * juntan en un párrafo.
     */
    private java.util.List<String> blocks(String template) {
        java.util.List<String> blocks = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : template.split("\r?\n")) {
            if (line.startsWith("«")) continue;
            if (line.isBlank()) {
                if (current.length() > 0) {
                    blocks.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            // Los títulos y las viñetas son bloques por sí mismos: no se pegan al
            // párrafo anterior aunque no haya una línea en blanco por medio.
            boolean ownBlock = line.startsWith("#") || line.startsWith("- ") || line.startsWith("[[");
            if (ownBlock && current.length() > 0) {
                blocks.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) current.append(' ');
            current.append(line.trim());
            if (ownBlock) {
                blocks.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) blocks.add(current.toString());
        return blocks;
    }

    /** Sustituye los {@code {{campos}}}; lo que no conozca se queda como está. */
    private String fill(String text, Map<String, String> values) {
        Matcher matcher = FIELD.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            if (value == null) {
                log.warn("La plantilla del contrato usa un campo que no existe: {}", matcher.group(0));
                value = matcher.group(0);
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    // --- La composición ----------------------------------------------------

    private Element compose(String block) {
        if (block.equals("[[firmas]]")) return signatures();

        if (block.startsWith("## ")) {
            Paragraph heading = new Paragraph(block.substring(3), Pdfs.H2);
            heading.setSpacingBefore(14);
            heading.setSpacingAfter(4);
            return heading;
        }
        if (block.startsWith("# ")) {
            Paragraph title = new Paragraph(block.substring(2), Pdfs.TITLE);
            title.setAlignment(Element.ALIGN_CENTER);
            title.setSpacingAfter(16);
            return title;
        }
        if (block.startsWith("- ")) {
            Paragraph bullet = rich("•   " + block.substring(2));
            bullet.setIndentationLeft(14);
            bullet.setSpacingAfter(3);
            return bullet;
        }
        Paragraph paragraph = rich(block);
        paragraph.setAlignment(Element.ALIGN_JUSTIFIED);
        paragraph.setSpacingAfter(8);
        return paragraph;
    }

    /** Un párrafo con los tramos entre ** en negrita. */
    private Paragraph rich(String text) {
        Paragraph paragraph = new Paragraph();
        paragraph.setLeading(14);
        boolean bold = false;
        for (String piece : text.split("\\*\\*", -1)) {
            if (!piece.isEmpty()) {
                paragraph.add(new Phrase(piece, bold ? Pdfs.BODY_BOLD : Pdfs.BODY));
            }
            bold = !bold;
        }
        return paragraph;
    }

    /** Las dos columnas de firmas, al final. */
    private Element signatures() {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setSpacingBefore(36);
        table.addCell(signature("EL ARRENDADOR"));
        table.addCell(signature("EL ARRENDATARIO"));
        return table;
    }

    private PdfPCell signature(String who) {
        Paragraph paragraph = new Paragraph();
        paragraph.add(new Phrase("\n\n\n_______________________________\n", Pdfs.BODY));
        paragraph.add(new Phrase(who, Pdfs.LABEL));
        PdfPCell cell = Pdfs.plain(paragraph);
        cell.setPaddingRight(16);
        return cell;
    }

    // --- Los datos ---------------------------------------------------------

    private Map<String, String> values(RentalAgreement rental, InvoiceIssuer.Issuer issuer) {
        StorageUnit unit = rental.getStorageUnit();
        Client client = rental.getClient();
        boolean vatApplicable = unit != null && unit.isVatApplicable();
        VatUtils.Breakdown rent = VatUtils.breakdown(rental.getMonthlyRent(), vatApplicable);
        BigDecimal deposit = rental.getSecurityDeposit();

        Map<String, String> values = new HashMap<>();
        values.put("fecha_larga", Pdfs.longDay(rental.getStartDate() == null ? LocalDate.now() : rental.getStartDate()));
        values.put("lugar", orMissing(issuer.city()));

        values.put("arrendador_nombre", orMissing(issuer.name()));
        values.put("arrendador_nif", orMissing(issuer.taxId()));
        values.put("arrendador_direccion", orMissing(issuer.address()));
        values.put("arrendador_ciudad", orMissing(issuer.city()));
        values.put("arrendador_email", orMissing(issuer.email()));
        values.put("arrendador_telefono", orMissing(issuer.phone()));
        values.put("arrendador_iban", orMissing(issuer.iban()));

        values.put("arrendatario_nombre", client == null ? orMissing(null) : client.getFullName());
        values.put("arrendatario_nif", client == null ? orMissing(null) : orMissing(client.getDocumentId()));
        values.put("arrendatario_direccion", client == null ? orMissing(null) : orMissing(client.getAddress()));
        values.put("arrendatario_email", client == null ? orMissing(null) : orMissing(client.getEmail()));
        values.put("arrendatario_telefono", client == null ? orMissing(null) : orMissing(client.getPhone()));
        values.put("coarrendatario", coTenant(rental.getCoClient()));

        values.put("unidad_numero", unit == null ? orMissing(null) : unit.getUnitNumber());
        values.put("unidad_nombre", unit == null ? orMissing(null) : unit.getName());
        values.put("unidad_metros", unit == null || unit.getSizeSquareMeters() == null
                ? orMissing(null) : trimZeros(unit.getSizeSquareMeters()));
        values.put("unidad_direccion", unit == null ? orMissing(null) : orMissing(unit.getLocation()));

        values.put("contrato_numero", orMissing(rental.getAgreementNumber()));
        values.put("fecha_inicio", Pdfs.day(rental.getStartDate()));
        values.put("fecha_fin", Pdfs.day(rental.getEndDate()));
        values.put("duracion", rental.getEndDate() == null
                ? "se prorrogará por periodos mensuales mientras ninguna de las partes lo denuncie"
                : "termina el " + Pdfs.day(rental.getEndDate()));

        values.put("renta_base", Pdfs.euros(rent.base()));
        values.put("renta_iva", vatApplicable ? Pdfs.euros(rent.vat()) : Pdfs.euros(BigDecimal.ZERO));
        values.put("renta_total", Pdfs.euros(rent.total()));
        values.put("iva_texto", vatApplicable
                ? "21 %, tipo general vigente"
                : "operación exenta, artículo 20.Uno.23º de la Ley 37/1992");
        values.put("dia_cobro", String.valueOf(rental.getBillingDayOfMonth()));

        values.put("fianza", deposit == null ? Pdfs.euros(BigDecimal.ZERO) : Pdfs.euros(deposit));
        values.put("fianza_texto", deposit == null || deposit.signum() == 0
                ? "No se establece fianza."
                : "EL ARRENDATARIO entrega a EL ARRENDADOR la cantidad de " + Pdfs.euros(deposit)
                  + " en concepto de fianza.");
        return values;
    }

    /** El párrafo del segundo titular, o nada cuando el contrato es de uno solo. */
    private String coTenant(Client coClient) {
        if (coClient == null) return "";
        return "Y de otra parte, **" + coClient.getFullName() + "**, con NIF "
               + orMissing(coClient.getDocumentId())
               + ", que interviene igualmente como ARRENDATARIO y responde solidariamente "
               + "de las obligaciones de este contrato.";
    }

    /** 5.0 m² queda raro en un contrato; 5 m², no. */
    private String trimZeros(Double size) {
        if (size == size.longValue()) return String.valueOf(size.longValue());
        return String.valueOf(size);
    }
}
