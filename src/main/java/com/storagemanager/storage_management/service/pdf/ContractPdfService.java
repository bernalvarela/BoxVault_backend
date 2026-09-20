package com.storagemanager.storage_management.service.pdf;

import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Chunk;
import org.openpdf.text.Element;
import org.openpdf.text.Image;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import com.storagemanager.storage_management.config.InvoicingProperties;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.service.InvoiceIssuer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * El contrato de alquiler en PDF, a partir de una plantilla de texto.
 * <p>
 * El texto legal vive en la plantilla y no aquí: cambiar una cláusula, añadir
 * una cuenta nueva o corregir una errata se hace desde la pantalla de plantillas,
 * sin recompilar ni desplegar nada.
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
public class ContractPdfService {

    private static final Pattern FIELD = Pattern.compile("\\{\\{([a-z_]+)}}");

    /**
     * [[si:campo]] ... [[fin]]: lo de dentro sólo sale si ese campo tiene algo.
     * <p>
     * Es lo que permite que una misma plantilla sirva para contratos que no son
     * iguales: la cláusula del fiador solidario sólo aparece cuando hay fiador, y
     * la de los gastos de comunidad sólo cuando se pactaron. Sin esto haría falta
     * una plantilla por combinación, y mantener el mismo texto legal en cuatro
     * sitios es la forma segura de que un día digan cosas distintas.
     */
    private static final Pattern IF_FIELD = Pattern.compile("\\[\\[si:([a-z_]+)]]");
    private static final String END_IF = "[[fin]]";

    /**
     * La misma condición, pero dentro de una frase:
     * {@code ...la parte arrendataria[[si:fiador]] y {{fiador}} como fiador
     * solidario[[fin]], que firman...}
     * <p>
     * Empezó valiendo sólo para párrafos enteros y se quedaba corta: media
     * cláusula del fiador no es un párrafo aparte, es una coletilla dentro de la
     * frase, y escribirla como párrafo suelto rompe el texto legal. Si la
     * condición no se cumple desaparece el trozo entero, marcas incluidas; si se
     * cumple, se van sólo las marcas.
     * <p>
     * No anida: dentro de un trozo condicional no cabe otro. Los dos extremos
     * tienen que estar en el mismo párrafo — abrir aquí y cerrar tres párrafos
     * más abajo sigue siendo el caso de siempre, el de párrafos enteros.
     */
    private static final Pattern INLINE_IF =
            Pattern.compile("\\[\\[si:([a-z_]+)]](.*?)\\[\\[fin]]", Pattern.DOTALL);

    /**
     * [[firmas]] o [[firmas:LOS ARRENDADORES|LA ARRENDATARIA]], cuando quien
     * firma no es un señor y un señor: dos propietarios, una arrendataria, una
     * empresa. Sin nada detrás, los rótulos de siempre.
     */
    private static final Pattern SIGNATURES = Pattern.compile("\\[\\[firmas(?::([^|\\]]*)\\|([^\\]]*))?]]");

    /** [[imagen:logo]] o [[imagen:logo|40]], donde 40 es el ancho en % de la caja de texto. */
    private static final Pattern IMAGE = Pattern.compile("\\[\\[imagen:([^|\\]]+)(?:\\|\\s*(\\d{1,3})\\s*)?]]");

    /** Un punto de una lista numerada: "1. ", "2) ". El número lo pone el compositor. */
    private static final Pattern NUMBERED = Pattern.compile("^\\d{1,3}[.)]\\s+(.*)$");

    /**
     * Los atributos que puede llevar un bloque delante: [centro], [pequeño]...
     * Se admiten varios seguidos. Un corchete simple no choca ni con {{campo}} ni
     * con [[marca]], así que no hay que escapar nada.
     */
    private static final Pattern ATTRIBUTE = Pattern.compile("^\\[([a-záéíóúñ-]+)]\\s*");

    /**
     * Los tramos con formato dentro de un párrafo. El orden importa: la negrita
     * se busca antes que la cursiva, o "**algo**" se leería como una cursiva que
     * empieza y acaba en asterisco.
     */
    private static final Pattern INLINE = Pattern.compile("\\*\\*(.+?)\\*\\*|\\*(.+?)\\*|__(.+?)__");


    public byte[] render(RentalAgreement rental, InvoiceIssuer.Issuer issuer, String template) {
        return render(rental, issuer, template, name -> null);
    }

    /**
     * Igual, pero sabiendo de dónde sacar las imágenes que la plantilla pida por
     * su nombre ({@code [[imagen:logo]]}). Quien llama decide de dónde salen; el
     * compositor sólo las coloca.
     */
    public byte[] render(RentalAgreement rental, InvoiceIssuer.Issuer issuer, String template,
                         Function<String, byte[]> images) {
        Map<String, String> values = ContractFields.of(rental, issuer);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document pdf = new Document(PageSize.A4, 56, 56, 56, 56);
        try {
            PdfWriter.getInstance(pdf, out);
            pdf.addTitle("Contrato de arrendamiento " + rental.getAgreementNumber());
            pdf.addCreator("BoxVault");
            pdf.open();
            // El número de los puntos de una lista lo lleva el compositor y no la
            // plantilla: así reordenar dos cláusulas no obliga a renumerarlas a
            // mano, que es donde siempre se cuela un "3." repetido.
            int ordinal = 0;
            // Dentro de un [[si:campo]] que no se cumple, los bloques se leen
            // pero no se pintan, hasta el [[fin]].
            boolean skipping = false;
            boolean pintado = false;
            for (String block : blocks(template)) {
                String bare = block.trim();
                Matcher condition = IF_FIELD.matcher(bare);
                if (condition.matches()) {
                    skipping = !hasValue(values, condition.group(1));
                    continue;
                }
                if (bare.equals(END_IF)) {
                    skipping = false;
                    continue;
                }
                if (skipping) continue;

                // Las condiciones que empiezan y acaban dentro de este mismo
                // párrafo se resuelven aquí; un párrafo que era sólo condición y
                // no se cumple se queda sin nada que pintar.
                block = inlineConditions(block, values);
                if (block.isBlank()) continue;

                String text = fill(block, values);
                ordinal = NUMBERED.matcher(stripAttributes(text).text()).matches() ? ordinal + 1 : 0;
                if (text.trim().equals("[[pagina]]")) {
                    pdf.newPage();
                    continue;
                }
                pdf.add(compose(text, images, ordinal));
                pintado = true;
            }
            // Una plantilla cuyas condiciones no se cumplen ninguna se quedaría
            // sin una sola página, y un PDF sin páginas no se puede ni cerrar.
            if (!pintado) pdf.add(new Paragraph(" "));
        } catch (DocumentException e) {
            throw new IllegalStateException(
                    "No se pudo componer el contrato " + rental.getAgreementNumber(), e);
        } finally {
            if (pdf.isOpen()) pdf.close();
        }
        return out.toByteArray();
    }

    // --- La plantilla ------------------------------------------------------

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
            // Un bloque propio no se pega al párrafo anterior aunque no haya una
            // línea en blanco por medio: títulos, puntos de lista, marcas y
            // cualquier línea que traiga atributos delante.
            String bare = stripAttributes(line.trim()).text();
            boolean ownBlock = line.startsWith("#") || bare.startsWith("- ") || line.startsWith("[[")
                    || line.startsWith("[") || NUMBERED.matcher(bare).matches();
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

    /**
     * Resuelve las condiciones que caben dentro de un párrafo. Lo que sobra se
     * va limpio: sin dobles espacios ni un espacio colgando antes de la coma.
     */
    private String inlineConditions(String block, Map<String, String> values) {
        Matcher matcher = INLINE_IF.matcher(block);
        StringBuilder result = new StringBuilder();
        boolean any = false;
        while (matcher.find()) {
            any = true;
            String kept = hasValue(values, matcher.group(1)) ? matcher.group(2) : "";
            matcher.appendReplacement(result, Matcher.quoteReplacement(kept));
        }
        if (!any) return block;
        matcher.appendTail(result);
        return result.toString()
                .replaceAll("[ \\t]{2,}", " ")
                .replaceAll("[ \\t]+([.,;:)])", "$1")
                .trim();
    }

    /**
     * Si ese campo tiene algo que decir.
     * <p>
     * Una línea de puntos no cuenta: {@code ..........} es lo que se imprime
     * cuando falta un dato, y un dato que falta no debe encender una cláusula.
     */
    private boolean hasValue(Map<String, String> values, String field) {
        String value = values.get(field);
        return value != null && !value.isBlank() && !value.equals(InvoicingProperties.MISSING);
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

    private Element compose(String block, Function<String, byte[]> images, int ordinal) {
        Styled styled = stripAttributes(block);
        String text = styled.text();

        Matcher firmas = SIGNATURES.matcher(text);
        if (firmas.matches()) {
            return signatures(
                    firmas.group(1) == null ? "EL ARRENDADOR" : firmas.group(1).trim(),
                    firmas.group(2) == null ? "EL ARRENDATARIO" : firmas.group(2).trim());
        }

        Matcher image = IMAGE.matcher(text);
        if (image.matches()) return image(image.group(1).trim(), image.group(2), images);

        if (text.startsWith("### ")) {
            return heading(text.substring(4), styled.size(11.5f), 10, 3, styled.alignment(Element.ALIGN_LEFT));
        }
        if (text.startsWith("## ")) {
            return heading(text.substring(3), styled.size(11f), 14, 4, styled.alignment(Element.ALIGN_LEFT));
        }
        if (text.startsWith("# ")) {
            return heading(text.substring(2), styled.size(18f), 0, 16, styled.alignment(Element.ALIGN_CENTER));
        }

        // Los puntos de una lista: la viñeta o el número, y el texto sangrado a la
        // misma altura, de modo que la segunda línea no se mete debajo del punto.
        Matcher numbered = NUMBERED.matcher(text);
        if (numbered.matches()) {
            return item(ordinal + ".", numbered.group(1), styled);
        }
        if (text.startsWith("- ")) {
            return item("•", text.substring(2), styled);
        }

        Paragraph paragraph = rich(text, styled.size(10f));
        paragraph.setAlignment(styled.alignment(Element.ALIGN_JUSTIFIED));
        paragraph.setSpacingAfter(8);
        return paragraph;
    }

    /** Un título de cualquiera de los tres niveles. */
    private Paragraph heading(String text, float size, float before, float after, int alignment) {
        Paragraph heading = new Paragraph(text, Pdfs.font(size, org.openpdf.text.Font.BOLD, Pdfs.INK));
        heading.setAlignment(alignment);
        heading.setSpacingBefore(before);
        heading.setSpacingAfter(after);
        return heading;
    }

    /** Un punto de lista: su marca y el texto, con sangría francesa. */
    private Paragraph item(String bullet, String text, Styled styled) {
        float size = styled.size(10f);
        Paragraph paragraph = rich(bullet + "   " + text, size);
        paragraph.setIndentationLeft(14 + size);
        // La sangría francesa: la primera línea sale hacia la izquierda, así que
        // la marca queda fuera y el texto alineado consigo mismo.
        paragraph.setFirstLineIndent(-(size + 6));
        paragraph.setAlignment(styled.alignment(Element.ALIGN_LEFT));
        paragraph.setSpacingAfter(3);
        return paragraph;
    }

    /**
     * Lo que un bloque trae delante entre corchetes —[centro], [pequeño]— y lo
     * que queda después de quitarlo.
     *
     * @param alignment alineación pedida, o -1 si el bloque no dijo nada
     * @param size      tamaño de letra pedido, o -1
     */
    private record Styled(String text, int alignment, float size) {
        int alignment(int byDefault) { return alignment < 0 ? byDefault : alignment; }
        float size(float byDefault) { return size < 0 ? byDefault : size; }
    }

    /** Separa los atributos del texto. Un atributo que no se conozca se deja pasar tal cual. */
    private Styled stripAttributes(String block) {
        String text = block;
        int alignment = -1;
        float size = -1f;

        Matcher matcher = ATTRIBUTE.matcher(text);
        while (matcher.find()) {
            String name = matcher.group(1);
            Integer align = switch (name) {
                case "izquierda" -> Element.ALIGN_LEFT;
                case "centro" -> Element.ALIGN_CENTER;
                case "derecha" -> Element.ALIGN_RIGHT;
                case "justificado" -> Element.ALIGN_JUSTIFIED;
                default -> null;
            };
            Float points = switch (name) {
                case "diminuto" -> 7f;
                case "pequeño" -> 8.5f;
                case "normal" -> 10f;
                case "grande" -> 13f;
                case "enorme" -> 16f;
                default -> null;
            };
            if (align == null && points == null) break; // no es un atributo: es texto
            if (align != null) alignment = align;
            if (points != null) size = points;
            text = text.substring(matcher.end());
            matcher = ATTRIBUTE.matcher(text);
        }
        return new Styled(text, alignment, size);
    }

    /**
     * Un párrafo con sus tramos resaltados: {@code **negrita**}, {@code *cursiva*}
     * y {@code __subrayado__}. Lo que queda fuera va en redonda.
     */
    private Paragraph rich(String text, float size) {
        Paragraph paragraph = new Paragraph();
        // El interlineado sigue al tamaño: con 1,4 el texto respira igual sea
        // cual sea la letra, que es lo que se pierde al fijarlo en 14 puntos.
        paragraph.setLeading(size * 1.4f);

        Matcher matcher = INLINE.matcher(text);
        int at = 0;
        while (matcher.find()) {
            if (matcher.start() > at) {
                addText(paragraph, text.substring(at, matcher.start()), body(size, org.openpdf.text.Font.NORMAL));
            }
            if (matcher.group(1) != null) {
                paragraph.add(new Phrase(matcher.group(1), body(size, org.openpdf.text.Font.BOLD)));
            } else if (matcher.group(2) != null) {
                paragraph.add(new Phrase(matcher.group(2), body(size, org.openpdf.text.Font.ITALIC)));
            } else {
                paragraph.add(new Phrase(matcher.group(3), body(size, org.openpdf.text.Font.UNDERLINE)));
            }
            at = matcher.end();
        }
        if (at < text.length()) {
            addText(paragraph, text.substring(at), body(size, org.openpdf.text.Font.NORMAL));
        }
        return paragraph;
    }

    /**
     * Texto que puede traer saltos de línea dentro, como el inventario de un
     * piso: cada línea es una línea. Sin esto, un campo con tres renglones salía
     * todo seguido.
     */
    private void addText(Paragraph paragraph, String text, org.openpdf.text.Font font) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) paragraph.add(Chunk.NEWLINE);
            if (!lines[i].isEmpty()) paragraph.add(new Phrase(lines[i], font));
        }
    }

    private static org.openpdf.text.Font body(float size, int style) {
        return Pdfs.font(size, style, Pdfs.INK);
    }

    /**
     * Una imagen de la plantilla, centrada y a lo ancho que se le pida (en % de
     * la caja de texto; por defecto la mitad).
     * <p>
     * Si la imagen no está —se borró, o el nombre está mal escrito— no revienta
     * el contrato: deja dicho en su sitio qué falta, igual que un campo que no
     * existe. Un contrato al que le falta el logotipo se firma igual; uno que no
     * se puede generar, no.
     */
    private Element image(String name, String widthPercent, Function<String, byte[]> images) {
        byte[] bytes = images == null ? null : images.apply(name);
        if (bytes == null || bytes.length == 0) {
            Paragraph missing = new Paragraph("[falta la imagen \"" + name + "\"]", Pdfs.SMALL);
            missing.setAlignment(Element.ALIGN_CENTER);
            return missing;
        }
        try {
            Image image = Image.getInstance(bytes);
            float percent = widthPercent == null ? 50f : Math.min(100f, Math.max(5f, Float.parseFloat(widthPercent)));
            // scaleToFit sobre el ancho de la caja de texto de un A4 con los
            // márgenes de este documento; el alto se deja libre y se ajusta solo.
            float boxWidth = PageSize.A4.getWidth() - 112f;
            image.scaleToFit(boxWidth * percent / 100f, PageSize.A4.getHeight());
            image.setAlignment(Element.ALIGN_CENTER);
            Paragraph holder = new Paragraph();
            holder.add(image);
            holder.setAlignment(Element.ALIGN_CENTER);
            holder.setSpacingBefore(6);
            holder.setSpacingAfter(6);
            return holder;
        } catch (Exception e) {
            log.warn("No se pudo colocar la imagen {} de la plantilla: {}", name, e.getMessage());
            Paragraph broken = new Paragraph("[no se pudo leer la imagen \"" + name + "\"]", Pdfs.SMALL);
            broken.setAlignment(Element.ALIGN_CENTER);
            return broken;
        }
    }

    /** Las dos columnas de firmas, al final, con el rótulo que pida la plantilla. */
    private Element signatures(String left, String right) {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setSpacingBefore(36);
        table.addCell(signature(left));
        table.addCell(signature(right));
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

}
