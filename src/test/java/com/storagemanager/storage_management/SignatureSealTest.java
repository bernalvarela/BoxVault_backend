package com.storagemanager.storage_management;

import com.storagemanager.storage_management.config.SigningProperties;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.service.InvoiceIssuer;
import com.storagemanager.storage_management.service.pdf.ContractFields;
import com.storagemanager.storage_management.service.pdf.ContractPdfService;
import com.storagemanager.storage_management.service.signing.SignatureSealService;
import com.storagemanager.storage_management.service.signing.SigningKeyStore;
import com.storagemanager.storage_management.service.storage.FileStorage;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.NoSuchFileException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * El contrato firmado: las firmas en su sitio, la hoja de evidencias detrás y
 * un sello que cuadra con el documento entero y deja de cuadrar si se toca un
 * solo byte. Sin Spring y sin red: el almacén de ficheros es un mapa y el sello
 * de tiempo está apagado.
 */
class SignatureSealTest {

    /** Un almacén en memoria con la misma pinta de "no existe" que el disco. */
    static final class MemoryStorage implements FileStorage {
        final Map<String, byte[]> objects = new HashMap<>();

        @Override
        public void put(String key, String contentType, long sizeBytes, InputStream content) {
            try {
                objects.put(key, content.readAllBytes());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public InputStream open(String key) {
            byte[] bytes = objects.get(key);
            if (bytes == null) throw new UncheckedIOException(new NoSuchFileException(key));
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void delete(String key) {
            objects.remove(key);
        }
    }

    private static SigningProperties properties() {
        SigningProperties properties = new SigningProperties();
        properties.setKeystorePassword("prueba");
        properties.setTsaEnabled(false);
        return properties;
    }

    private final MemoryStorage storage = new MemoryStorage();
    private final SignatureSealService sealer =
            new SignatureSealService(new SigningKeyStore(storage, properties()), properties());

    private static final InvoiceIssuer.Issuer ISSUER = new InvoiceIssuer.Issuer(
            "Bernal Varela Gómez", "46906413Y", "Avenida del Pasaje 29", "Oleiros (A Coruña)",
            null, null, null, false, "Bernal Varela Gómez (NIF 46906413Y)",
            List.of(com.storagemanager.storage_management.model.Owner.builder()
                    .fullName("Bernal Varela Gómez").documentId("46906413Y").build()));

    @Test
    void theSealedContractVerifiesAndCarriesTheEvidencePage() throws IOException {
        byte[] sealed = sealSample().content();

        assertTrue(SignatureSealService.verify(sealed), "el sello tiene que cuadrar con el documento entero");

        PdfReader reader = new PdfReader(sealed);
        String last = new PdfTextExtractor(reader).getTextFromPage(reader.getNumberOfPages()).replaceAll("\\s+", " ");
        String all = wholeText(sealed);
        assertTrue(all.contains("Hoja de evidencias de la firma electrónica"), all);
        assertTrue(all.contains("Ana Gómez Pérez"), all);
        assertTrue(all.contains("Identidad comprobada"), all);
        assertTrue(all.contains("enlace personal"), "el firmante con enlace: " + all);
        assertTrue(all.contains("código de un solo uso"), "cómo se comprobó que era él: " + all);
        assertFalse(last.isBlank());
    }

    @Test
    void changingOneByteBreaksTheSeal() {
        byte[] sealed = sealSample().content();
        byte[] tampered = sealed.clone();
        // Un byte del contenido de la primera página, lejos del hueco de la firma CMS.
        int at = indexOf(tampered, "stream".getBytes()) + 20;
        tampered[at] = (byte) (tampered[at] ^ 0x01);
        assertFalse(SignatureSealService.verify(tampered), "un PDF tocado no puede pasar por sellado");
    }

    @Test
    void withoutTimestampItSaysSo() {
        SignatureSealService.Sealed sealed = sealSample();
        assertFalse(sealed.timestamped());
        assertNull(sealed.timestampProblem(), "apagado no es un problema: no se pidió");
    }

    @Test
    void theCertificateIsCreatedOnceAndReused() {
        sealSample();
        byte[] first = storage.objects.values().stream().findFirst().orElseThrow();
        new SignatureSealService(new SigningKeyStore(storage, properties()), properties()).seal(request());
        assertEquals(1, storage.objects.size(), "un solo almacén de claves");
        assertArrayEquals(first, storage.objects.values().iterator().next(), "el certificado no se regenera");
    }

    // ------------------------------------------------------------------

    private SignatureSealService.Sealed sealSample() {
        return sealer.seal(request());
    }

    private SignatureSealService.SealRequest request() {
        RentalAgreement rental = ContractFields.sampleRental();
        ContractPdfService.Rendered rendered = new ContractPdfService().renderForSigning(
                rental, ISSUER, "# Contrato\n\nEl contrato de prueba.\n\n[[firmas]]", name -> null, null);
        assertFalse(rendered.slots().isEmpty(), "el pie de firmas tiene que dejar huecos");

        byte[] png = signaturePng();
        String pngHash = sha256(png);
        List<SignatureSealService.SignerEvidence> signers = rendered.slots().stream()
                .map(slot -> new SignatureSealService.SignerEvidence(slot.key(),
                        slot.key().startsWith("ARRENDADOR") ? "PARTE ARRENDADORA" : "PARTE ARRENDATARIA",
                        slot.key().startsWith("ARRENDADOR") ? "Bernal Varela Gómez" : "Ana Gómez Pérez",
                        "12345678Z", null,
                        slot.key().startsWith("ARRENDADOR"),
                        Instant.parse("2026-10-16T10:15:30Z"), "192.168.1.20", "Mozilla/5.0 (Tablet)",
                        slot.key().startsWith("ARRENDADOR"), "admin", "admin",
                        sha256(rendered.content()), pngHash,
                        slot.key().startsWith("ARRENDADOR") ? null : "código de un solo uso mandado por BoxVault a su correo ana@ejemplo.es",
                        png))
                .toList();
        return new SignatureSealService.SealRequest("Contrato de arrendamiento CON-PRUEBA · Trastero 3",
                rendered.content(), sha256(rendered.content()), rendered.slots(), signers);
    }

    private static byte[] signaturePng() {
        BufferedImage image = new BufferedImage(300, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(0x1e, 0x3a, 0x8a));
        g.setStroke(new BasicStroke(3));
        g.drawLine(10, 80, 120, 20);
        g.drawLine(120, 20, 290, 70);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static String wholeText(byte[] pdf) throws IOException {
        PdfReader reader = new PdfReader(pdf);
        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        StringBuilder text = new StringBuilder();
        for (int page = 1; page <= reader.getNumberOfPages(); page++) {
            text.append(extractor.getTextFromPage(page)).append('\n');
        }
        return text.toString().replaceAll("\\s+", " ");
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        throw new AssertionError("no está");
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
