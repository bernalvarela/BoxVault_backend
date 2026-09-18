package com.storagemanager.storage_management.service.pdf;

import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.PdfPCell;

import java.awt.Color;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Lo que comparten la factura y el contrato: los tipos de letra, los colores y
 * el formato español de importes y fechas. Nada de esto es específico de un
 * documento; tenerlo junto evita que cada PDF invente su propia tipografía.
 * <p>
 * Se usan las fuentes base de PDF (Helvetica), que van dentro del propio
 * formato: no hay que empaquetar ningún .ttf ni depender de las fuentes del
 * sistema, que en una imagen nativa sobre Alpine no existen.
 */
public final class Pdfs {

    /** Español de España: 1.234,56 y los meses en su idioma. */
    public static final Locale ES = Locale.forLanguageTag("es-ES");

    public static final Color INK = new Color(0x1E, 0x29, 0x3B);
    public static final Color MUTED = new Color(0x64, 0x74, 0x8B);
    public static final Color LINE = new Color(0xCB, 0xD5, 0xE1);
    public static final Color BAND = new Color(0xF1, 0xF5, 0xF9);

    public static final Font TITLE = font(18, Font.BOLD, INK);
    public static final Font H2 = font(11, Font.BOLD, INK);
    public static final Font LABEL = font(8, Font.BOLD, MUTED);
    public static final Font BODY = font(10, Font.NORMAL, INK);
    public static final Font BODY_BOLD = font(10, Font.BOLD, INK);
    public static final Font BODY_ITALIC = font(10, Font.ITALIC, INK);
    public static final Font BODY_BOLD_ITALIC = font(10, Font.BOLDITALIC, INK);
    public static final Font BODY_UNDERLINE = font(10, Font.UNDERLINE, INK);
    public static final Font SMALL = font(8, Font.NORMAL, MUTED);
    public static final Font TOTAL = font(13, Font.BOLD, INK);

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /**
     * Los meses, escritos aquí y no pedidos al JDK.
     * <p>
     * La imagen nativa sólo lleva los datos del idioma con el que se construye,
     * así que {@code MMMM} con la configuración española devolvía "Sep" en el
     * servidor mientras en local salía "septiembre". Lo mismo valía para los
     * importes: los separadores de miles y decimales salían a la inglesa. Un
     * documento que se entrega a un inquilino o a Hacienda no puede depender de
     * con qué banderas se compiló el binario.
     */
    private static final String[] MONTHS = {
            "enero", "febrero", "marzo", "abril", "mayo", "junio",
            "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"
    };

    private Pdfs() {}

    public static Font font(float size, int style, Color color) {
        return FontFactory.getFont(FontFactory.HELVETICA, size, style, color);
    }

    /**
     * 1.234,56 € — con el espacio antes del símbolo, como se escribe en español.
     * Los separadores se ponen a mano (ver {@link #MONTHS}) en vez de pedirle el
     * formato español al JDK, que en la imagen nativa puede no estar.
     */
    public static String euros(BigDecimal amount) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setDecimalSeparator(',');
        symbols.setGroupingSeparator('.');
        DecimalFormat format = new DecimalFormat("#,##0.00", symbols);
        return format.format(amount == null ? BigDecimal.ZERO : amount) + " €";
    }

    /** 17/09/2026; un guion cuando no hay fecha. */
    public static String day(LocalDate date) {
        return date == null ? "—" : DAY.format(date);
    }

    /** 17 de septiembre de 2026. */
    public static String longDay(LocalDate date) {
        if (date == null) return "—";
        return date.getDayOfMonth() + " de " + MONTHS[date.getMonthValue() - 1] + " de " + date.getYear();
    }

    /** Septiembre de 2026, con la inicial en mayúscula. */
    public static String monthOf(int year, int month) {
        if (month < 1 || month > 12) return String.valueOf(year);
        return capitalize(MONTHS[month - 1]) + " de " + year;
    }

    public static String capitalize(String text) {
        if (text == null || text.isEmpty()) return text;
        return text.substring(0, 1).toUpperCase(ES) + text.substring(1);
    }

    /**
     * Celda sin bordes, para las tablas que sólo sirven para colocar cosas.
     * <p>
     * El contenido se añade con {@code addElement} y no por el constructor: así
     * la celda queda en modo compuesto y respeta la alineación y el interlineado
     * del párrafo. Por el constructor, la celda impone los suyos, y el bloque
     * que debía ir a la derecha aparece a la izquierda.
     */
    public static PdfPCell plain(Element content) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(org.openpdf.text.Rectangle.NO_BORDER);
        cell.setPadding(0);
        cell.addElement(content);
        return cell;
    }

    /** Una celda de las filas de la tabla de conceptos. */
    public static PdfPCell cell(String text, Font font, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBorder(org.openpdf.text.Rectangle.BOTTOM);
        cell.setBorderColor(LINE);
        cell.setHorizontalAlignment(alignment);
        cell.setPaddingTop(6);
        cell.setPaddingBottom(6);
        return cell;
    }

    /** Cabecera de la tabla de conceptos: fondo gris y letra pequeña. */
    public static PdfPCell head(String text, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(text.toUpperCase(ES), LABEL));
        cell.setBorder(org.openpdf.text.Rectangle.NO_BORDER);
        cell.setBackgroundColor(BAND);
        cell.setHorizontalAlignment(alignment);
        cell.setPadding(6);
        return cell;
    }

    /**
     * Un trozo de nombre de fichero a partir de un texto: sin tildes, sin
     * mayúsculas y con guiones. "Trastero 3" queda "trastero-3".
     * <p>
     * Los ficheros que genera la aplicación se acaban guardando en el disco de
     * alguien, así que el nombre tiene que decir qué son sin abrirlos.
     */
    public static String slug(String text) {
        if (text == null || text.isBlank()) return "";
        return java.text.Normalizer.normalize(text.trim(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(ES)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }

    /** Espacio vertical entre bloques. */
    public static Paragraph gap(float height) {
        Paragraph paragraph = new Paragraph(" ");
        paragraph.setLeading(height);
        return paragraph;
    }
}
