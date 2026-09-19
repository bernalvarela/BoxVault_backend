package com.storagemanager.storage_management;

import com.storagemanager.storage_management.service.InvoiceIssuer;
import com.storagemanager.storage_management.service.pdf.ContractFields;
import com.storagemanager.storage_management.service.pdf.ContractPdfService;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lo que una plantilla de contrato puede escribir y qué sale de ello.
 * <p>
 * Se comprueba leyendo el PDF, no confiando en que compile: el compositor tiene
 * ya bastantes marcas —títulos, listas, tamaños, alineación, saltos de página,
 * imágenes— y cada una es una forma de que un contrato salga mal sin que nadie
 * se entere hasta tenerlo firmado delante.
 */
class ContractMarkupTest {

    private static final InvoiceIssuer.Issuer ISSUER = new InvoiceIssuer.Issuer(
            "Comunidad de bienes Pasaxe 29", "E56424500", "Avenida del Pasaje 29",
            "Oleiros (A Coruña)", "pasaxe29@ejemplo.es", "600 000 000",
            "ES00 0000 0000 0000 0000 0000", true,
            "Bernal Varela Gómez (NIF 46906413Y) y Xiao Varela Gómez (NIF 46906414F)");

    private final ContractPdfService pdf = new ContractPdfService();

    private String textOf(byte[] document, int page) throws IOException {
        return new PdfTextExtractor(new PdfReader(document)).getTextFromPage(page);
    }

    /** El documento entero: un contrato largo reparte lo que se comprueba por varias páginas. */
    private String wholeTextOf(byte[] document) throws IOException {
        PdfReader reader = new PdfReader(document);
        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        StringBuilder text = new StringBuilder();
        for (int page = 1; page <= reader.getNumberOfPages(); page++) {
            text.append(extractor.getTextFromPage(page)).append('\n');
        }
        return text.toString();
    }

    @Test
    void theTemplateFieldsAreSubstituted() throws IOException {
        byte[] document = render("El arrendatario {{arrendatario_nombre}} paga {{renta_total}} al mes.");
        String text = textOf(document, 1);

        assertTrue(text.contains("Ana Gómez Pérez"), text);
        assertTrue(text.contains("55,00 €"), text);
        assertFalse(text.contains("{{"), "no debe quedar ningún campo sin sustituir: " + text);
    }

    @Test
    void anUnknownFieldIsPrintedAsItIsInsteadOfDisappearing() throws IOException {
        // Vale más una errata visible en el papel que un hueco que nadie nota.
        String text = textOf(render("Mide {{unidad_metross}} metros."), 1);
        assertTrue(text.contains("{{unidad_metross}}"), text);
    }

    @Test
    void aPageBreakStartsANewPage() throws IOException {
        byte[] document = render("Primera página.\n\n[[pagina]]\n\nSegunda página.");

        PdfReader reader = new PdfReader(document);
        assertEquals(2, reader.getNumberOfPages());
        assertTrue(textOf(document, 1).contains("Primera"), "la primera página");
        assertTrue(textOf(document, 2).contains("Segunda"), "la segunda página");
    }

    @Test
    void listsAreNumberedByTheComposerAndNotByTheTemplate() throws IOException {
        // Los tres puntos están escritos "1." en la plantilla a propósito: el
        // número lo pone el compositor, así que reordenar cláusulas no obliga a
        // renumerarlas a mano.
        String text = textOf(render("1. Primero\n1. Segundo\n1. Tercero"), 1);

        assertTrue(text.contains("1.   Primero"), text);
        assertTrue(text.contains("2.   Segundo"), text);
        assertTrue(text.contains("3.   Tercero"), text);
    }

    @Test
    void aBulletBreaksTheNumbering() throws IOException {
        String text = textOf(render("1. Primero\n\n- Una viñeta\n\n1. Vuelta a empezar"), 1);

        assertTrue(text.contains("1.   Primero"), text);
        assertTrue(text.contains("•   Una viñeta"), text);
        assertTrue(text.contains("1.   Vuelta a empezar"), "la lista vuelve a empezar: " + text);
    }

    @Test
    void attributesDoNotLeakIntoTheText() throws IOException {
        // Lo que se comprueba es que [centro] y [pequeño] se consumen como
        // atributos; que el párrafo salga centrado y en letra pequeña no se puede
        // leer del texto extraído, pero que la marca NO salga impresa, sí.
        String text = textOf(render("[centro][pequeño] Un aviso discreto."), 1);

        assertTrue(text.contains("Un aviso discreto."), text);
        assertFalse(text.contains("[centro]"), "el atributo no se imprime: " + text);
        assertFalse(text.contains("[pequeño]"), "el atributo no se imprime: " + text);
    }

    @Test
    void anUnknownAttributeIsLeftAloneBecauseItIsProbablyText() throws IOException {
        // "[Ley 29/1994]" al principio de una línea es texto, no un atributo.
        String text = textOf(render("[Ley 29/1994] regula estos arrendamientos."), 1);
        assertTrue(text.contains("[Ley 29/1994]"), text);
    }

    @Test
    void emphasisMarksAreNotPrinted() throws IOException {
        String text = textOf(render("Esto es **muy** *importante* y __queda dicho__."), 1);

        assertTrue(text.contains("muy"), text);
        assertTrue(text.contains("importante"), text);
        assertTrue(text.contains("queda dicho"), text);
        assertFalse(text.contains("**"), "los asteriscos no se imprimen: " + text);
        assertFalse(text.contains("__"), "los guiones bajos no se imprimen: " + text);
    }

    @Test
    void aMissingImageLeavesASignInsteadOfBreakingTheContract() throws IOException {
        // Un contrato sin logotipo se firma igual; uno que no se puede generar, no.
        String text = textOf(render("[[imagen:logo]]"), 1);
        assertTrue(text.contains("falta la imagen"), text);
    }

    @Test
    void commentsAndSignaturesBehave() throws IOException {
        String text = textOf(render("« esto no sale\nY esto sí.\n\n[[firmas]]"), 1);

        assertFalse(text.contains("esto no sale"), "los comentarios no se imprimen: " + text);
        assertTrue(text.contains("Y esto sí."), text);
        assertTrue(text.contains("EL ARRENDADOR"), text);
        assertTrue(text.contains("EL ARRENDATARIO"), text);
    }

    @Test
    void theHousingTemplateThatShipsWithTheAppRenders() throws IOException {
        // La plantilla del piso de Pasaxe 29, tal como se entrega. Se comprueba
        // entera porque es la que se va a copiar en la aplicación: un campo mal
        // escrito aquí sale impreso en un contrato que alguien firma.
        String template = new String(getClass().getClassLoader()
                .getResourceAsStream("plantillas/contrato-vivienda.txt").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        byte[] document = render(template);
        String text = wholeTextOf(document);

        assertFalse(text.contains("{{"), "ningún campo sin sustituir: " + text);
        assertFalse(text.contains("Hecha a partir del contrato"), "los comentarios no se imprimen");
        assertTrue(text.contains("CONTRATO DE ARRENDAMIENTO DE VIVIENDA"), text);
        assertTrue(text.contains("Ana Gómez Pérez"), "el inquilino: " + text);
        assertTrue(text.contains("660,00 €"), "la renta anual, calculada: " + text);
        assertTrue(text.contains("20,00 €"), "la comunidad, del contrato: " + text);
        assertTrue(text.contains("120,00 €"), "el IBI, del contrato: " + text);
        assertTrue(text.contains("lavabo con espejo"), "el inventario, de la ficha del piso: " + text);
        assertTrue(text.contains("LOS ARRENDADORES"), "el pie de firmas a medida: " + text);
        // "LA PARTE ARRENDATARIA" y no "LA ARRENDATARIA": la misma plantilla sirve
        // para un inquilino y para dos.
        assertTrue(text.contains("LA PARTE ARRENDATARIA"), text);
        assertFalse(text.contains("FIANZA SOLIDARIA"), "sin fiador, su cláusula no existe: " + text);
    }

    @Test
    void signatureLabelsCanBeChosenByTheTemplate() throws IOException {
        String text = textOf(render("[[firmas:LA EMPRESA|EL CLIENTE]]"), 1);
        assertTrue(text.contains("LA EMPRESA"), text);
        assertTrue(text.contains("EL CLIENTE"), text);
        assertFalse(text.contains("ARRENDADOR"), "no quedan los rótulos de siempre: " + text);
    }

    @Test
    void aConditionalBlockDisappearsWhenItsFieldIsEmpty() throws IOException {
        // El contrato de ejemplo no lleva fiador, así que su cláusula no existe.
        String template = "Antes.\n\n[[si:fiador]]\nAvala don Fulano.\n[[fin]]\n\nDespués.";
        String text = textOf(render(template), 1);

        assertTrue(text.contains("Antes."), text);
        assertTrue(text.contains("Después."), text);
        assertFalse(text.contains("Avala"), "la cláusula del fiador no sale: " + text);
        assertFalse(text.contains("[[si:"), "las marcas no se imprimen: " + text);
        assertFalse(text.contains("[[fin]]"), text);
    }

    @Test
    void aConditionalBlockStaysWhenItsFieldHasSomething() throws IOException {
        String template = "[[si:arrendatario_nombre]]\nArrienda {{arrendatario_nombre}}.\n[[fin]]";
        String text = textOf(render(template), 1);
        assertTrue(text.contains("Arrienda Ana Gómez Pérez."), text);
    }

    @Test
    void aFieldThatIsJustAGapCountsAsEmpty() throws IOException {
        // {{fiador}} sin fiador queda vacío; {{arrendador_email}} sin correo queda
        // en puntos suspensivos. Ninguno de los dos debe encender su cláusula.
        String text = textOf(render("[[si:fiador]]\nNo debería salir.\n[[fin]]"), 1);
        assertFalse(text.contains("No debería salir"), text);
    }

    private byte[] render(String template) {
        return pdf.render(ContractFields.sampleRental(), ISSUER, template);
    }
}
