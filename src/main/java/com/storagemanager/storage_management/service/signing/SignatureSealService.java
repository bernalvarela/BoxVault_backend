package com.storagemanager.storage_management.service.signing;

import com.storagemanager.storage_management.config.SigningProperties;
import com.storagemanager.storage_management.service.pdf.ContractPdfService.Slot;
import com.storagemanager.storage_management.service.pdf.Pdfs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Element;
import org.openpdf.text.Image;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.AcroFields;
import org.openpdf.text.pdf.ColumnText;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfDate;
import org.openpdf.text.pdf.PdfDictionary;
import org.openpdf.text.pdf.PdfName;
import org.openpdf.text.pdf.PdfPKCS7;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.PdfSignature;
import org.openpdf.text.pdf.PdfSignatureAppearance;
import org.openpdf.text.pdf.PdfStamper;
import org.openpdf.text.pdf.PdfString;
import org.openpdf.text.pdf.TSAClient;
import org.openpdf.text.pdf.TSAClientBouncyCastle;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Convierte el contrato congelado y las firmas de todos en el contrato firmado:
 * <ol>
 *   <li>estampa cada firma dibujada en su hueco del pie de firmas;</li>
 *   <li>añade al final la hoja de evidencias: quién firmó, cuándo, cómo, desde
 *       dónde, la huella del contrato que tenía delante y su firma;</li>
 *   <li>sella el resultado (PAdES, firma CMS separada) con el certificado de
 *       BoxVault y, si responde, con un sello de tiempo de un tercero.</li>
 * </ol>
 * Después del sello, cualquier cambio en el PDF —una coma— hace que el lector
 * de PDF diga que el documento se ha modificado.
 * <p>
 * No sabe nada de la base de datos: recibe lo que tiene que pintar y devuelve
 * bytes, así se puede probar sin levantar nada.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignatureSealService {

    /** Lo que se reserva en el PDF para la firma CMS: certificado, firma y sello de tiempo caben de sobra. */
    private static final int CONTENTS_SIZE = 20_000;

    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("dd/MM/yyyy 'a las' HH:mm:ss", Pdfs.ES).withZone(MADRID);

    private final SigningKeyStore keyStore;
    private final SigningProperties properties;

    /**
     * Lo que se sabe de cada firmante para la hoja de evidencias.
     *
     * @param key          su hueco en el pie de firmas ({@code ARRENDATARIO:1})
     * @param inPerson     si firmó en la tableta de quien gestiona
     * @param signaturePng su firma, tal como la dibujó
     */
    public record SignerEvidence(String key, String role, String fullName, String taxId, String onBehalfOf,
                                 boolean inPerson, Instant signedAt, String ip, String userAgent,
                                 boolean identityChecked, String identityCheckedBy, String witnessedBy,
                                 String documentSha256, String signatureSha256, byte[] signaturePng) {}

    /**
     * Lo que hay que sellar.
     *
     * @param title          "Contrato de arrendamiento CON-2026-03 · Trastero 3"
     * @param original       el PDF congelado, tal como se firmó
     * @param originalSha256 su huella
     */
    public record SealRequest(String title, byte[] original, String originalSha256,
                              List<Slot> slots, List<SignerEvidence> signers) {}

    /**
     * El contrato firmado.
     *
     * @param timestamped      si lleva sello de tiempo de un tercero
     * @param timestampProblem por qué no lo lleva, si se pidió y no se pudo
     */
    public record Sealed(byte[] content, boolean timestamped, String timestampProblem) {}

    public Sealed seal(SealRequest request) {
        byte[] stamped = stamp(request);
        return sign(stamped, request.title());
    }

    // --- Firmas y hoja de evidencias ---------------------------------------------

    private byte[] stamp(SealRequest request) {
        try {
            PdfReader reader = new PdfReader(request.original());
            int pages = reader.getNumberOfPages();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfStamper stamper = new PdfStamper(reader, out);

            for (SignerEvidence signer : request.signers()) {
                Slot slot = request.slots().stream()
                        .filter(s -> s.key().equals(signer.key()))
                        .findFirst().orElse(null);
                // Una plantilla sin [[firmas]] no deja huecos: entonces la firma
                // sale sólo en la hoja de evidencias, que es donde manda igual.
                if (slot == null || slot.page() < 1 || slot.page() > pages) continue;
                Image image = Image.getInstance(signer.signaturePng());
                float width = slot.urx() - slot.llx() - 8;
                float height = slot.ury() - slot.lly() - 4;
                image.scaleToFit(width, height);
                image.setAbsolutePosition(
                        slot.llx() + (slot.urx() - slot.llx() - image.getScaledWidth()) / 2,
                        slot.lly() + 2);
                stamper.getOverContent(slot.page()).addImage(image);
            }

            evidencePages(stamper, pages, request);
            stamper.close();
            return out.toByteArray();
        } catch (IOException | DocumentException e) {
            throw new IllegalStateException("No se pudieron estampar las firmas en el contrato", e);
        }
    }

    /** La hoja de evidencias, detrás de la última página; ocupa las páginas que haga falta. */
    private void evidencePages(PdfStamper stamper, int pages, SealRequest request) throws DocumentException, IOException {
        int page = pages + 1;
        stamper.insertPage(page, PageSize.A4);
        ColumnText column = new ColumnText(stamper.getOverContent(page));
        column.setSimpleColumn(56, 56, PageSize.A4.getWidth() - 56, PageSize.A4.getHeight() - 56);

        Paragraph title = new Paragraph("Hoja de evidencias de la firma electrónica", Pdfs.TITLE);
        title.setSpacingAfter(6);
        column.addElement(title);
        column.addElement(new Paragraph(request.title(), Pdfs.BODY_BOLD));
        column.addElement(text("Huella SHA-256 del contrato que firmaron las partes, tal como se les mostró "
                + "(antes de estampar las firmas y de añadir esta hoja):", Pdfs.BODY, 8));
        column.addElement(new Paragraph(request.originalSha256(), Pdfs.font(8.5f, org.openpdf.text.Font.BOLD, Pdfs.INK)));

        for (SignerEvidence signer : request.signers()) {
            Paragraph who = new Paragraph();
            who.setSpacingBefore(14);
            who.add(new Phrase(signer.role() + "  ", Pdfs.LABEL));
            who.add(new Phrase(signer.fullName()
                    + (signer.taxId() == null || signer.taxId().isBlank() ? "" : " · NIF " + signer.taxId()), Pdfs.BODY_BOLD));
            if (signer.onBehalfOf() != null) {
                who.add(new Phrase("\nFirma en nombre de " + signer.onBehalfOf(), Pdfs.BODY));
            }
            column.addElement(who);

            StringBuilder how = new StringBuilder();
            how.append("Firmó el ").append(WHEN.format(signer.signedAt())).append(" (hora de Madrid). ");
            if (signer.inPerson()) {
                how.append("En persona, en la tableta de ").append(orUnknown(signer.witnessedBy())).append(". ");
                how.append(signer.identityChecked()
                        ? "Identidad comprobada con su documento de identidad por " + orUnknown(signer.identityCheckedBy()) + "."
                        : "Sin comprobación de identidad anotada.");
            } else {
                how.append("Desde su propio dispositivo, con un enlace personal de un solo uso, "
                        + "tras teclear su documento de identidad, que coincidió con el de su ficha.");
            }
            column.addElement(text(how.toString(), Pdfs.BODY, 2));
            column.addElement(text("Dirección IP: " + orUnknown(signer.ip())
                    + " · Dispositivo: " + shorten(signer.userAgent(), 140), Pdfs.SMALL, 2));
            column.addElement(text("Contrato que tenía delante (SHA-256): " + orUnknown(signer.documentSha256()), Pdfs.SMALL, 0));
            column.addElement(text("Su firma (SHA-256): " + orUnknown(signer.signatureSha256()), Pdfs.SMALL, 0));

            Image image = Image.getInstance(signer.signaturePng());
            image.scaleToFit(170, 56);
            image.setAlignment(Element.ALIGN_LEFT);
            column.addElement(image);
        }

        column.addElement(text("Este documento lo ha sellado BoxVault con su certificado propio al completarse "
                + "la última firma. El sello garantiza que el PDF no ha cambiado desde entonces: cualquier "
                + "modificación hace que el lector de PDF lo marque como alterado. Si el sello incluye un sello "
                + "de tiempo de un tercero, el panel de firmas del lector lo muestra con su fecha.", Pdfs.SMALL, 18));

        int status = column.go();
        while (ColumnText.hasMoreText(status)) {
            page++;
            stamper.insertPage(page, PageSize.A4);
            column.setCanvas(stamper.getOverContent(page));
            column.setSimpleColumn(56, 56, PageSize.A4.getWidth() - 56, PageSize.A4.getHeight() - 56);
            status = column.go();
        }
    }

    private static Paragraph text(String text, org.openpdf.text.Font font, float before) {
        Paragraph paragraph = new Paragraph(text, font);
        paragraph.setSpacingBefore(before);
        paragraph.setLeading(font.getSize() * 1.35f);
        return paragraph;
    }

    private static String orUnknown(String value) {
        return value == null || value.isBlank() ? "no consta" : value;
    }

    private static String shorten(String value, int max) {
        if (value == null || value.isBlank()) return "no consta";
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    // --- El sello ---------------------------------------------------------------

    private Sealed sign(byte[] pdf, String title) {
        SigningKeyStore.Credentials credentials = keyStore.credentials();
        try {
            PdfReader reader = new PdfReader(pdf);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfStamper stamper = PdfStamper.createSignature(reader, out, null);
            PdfSignatureAppearance appearance = stamper.getSignatureAppearance();
            Calendar now = Calendar.getInstance();
            String reason = "Sellado al completarse la firma de todas las partes";
            appearance.setCrypto(null, credentials.chain(), null, PdfSignatureAppearance.WINCER_SIGNED);
            appearance.setReason(reason);
            appearance.setLocation("BoxVault");
            appearance.setSignDate(now);

            PdfSignature dictionary = new PdfSignature(PdfName.ADOBE_PPKLITE, PdfName.ADBE_PKCS7_DETACHED);
            dictionary.setReason(reason);
            dictionary.setLocation("BoxVault");
            dictionary.setName(PdfPKCS7.getSubjectFields(credentials.certificate()).getField("CN"));
            dictionary.setDate(new PdfDate(now));
            appearance.setCryptoDictionary(dictionary);

            Map<PdfName, Integer> reserved = new HashMap<>();
            reserved.put(PdfName.CONTENTS, CONTENTS_SIZE * 2 + 2);
            appearance.preClose(reserved);

            byte[] digest = digest(appearance.getRangeStream());

            String problem = null;
            byte[] encoded = null;
            if (properties.isTsaEnabled()) {
                try {
                    encoded = cms(credentials, digest, now, new TimeoutTsaClient(properties.getTsaUrl()));
                } catch (Exception e) {
                    problem = "El servidor de sellos de tiempo (" + properties.getTsaUrl() + ") no respondió: "
                            + rootMessage(e);
                    log.warn("Sellando sin sello de tiempo: {}", problem);
                }
            }
            if (encoded == null) encoded = cms(credentials, digest, now, null);
            if (encoded.length > CONTENTS_SIZE) {
                throw new IllegalStateException("La firma CMS no cabe en el hueco reservado (" + encoded.length + " bytes)");
            }

            PdfDictionary contents = new PdfDictionary();
            contents.put(PdfName.CONTENTS, new PdfString(Arrays.copyOf(encoded, CONTENTS_SIZE)).setHexWriting(true));
            appearance.close(contents);

            boolean timestamped = properties.isTsaEnabled() && problem == null;
            log.info("Sellado \"{}\" {}", title, timestamped ? "con sello de tiempo" : "sin sello de tiempo");
            return new Sealed(out.toByteArray(), timestamped, problem);
        } catch (IOException | DocumentException | java.security.GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo sellar el contrato firmado", e);
        }
    }

    /** La firma CMS de ese resumen; cada intento con un PdfPKCS7 nuevo, que no se puede reutilizar. */
    private static byte[] cms(SigningKeyStore.Credentials credentials, byte[] digest, Calendar when, TSAClient tsa)
            throws java.security.GeneralSecurityException {
        PdfPKCS7 pkcs7 = new PdfPKCS7(credentials.key(), credentials.chain(), null, "SHA256", null, false);
        byte[] attributes = pkcs7.getAuthenticatedAttributeBytes(digest, when, null);
        pkcs7.update(attributes, 0, attributes.length);
        return pkcs7.getEncodedPKCS7(digest, when, tsa, null);
    }

    private static byte[] digest(InputStream data) throws IOException, java.security.NoSuchAlgorithmException {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        int read;
        while ((read = data.read(buffer)) > 0) sha256.update(buffer, 0, read);
        return sha256.digest();
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    /**
     * El cliente de sellos de tiempo de OpenPDF, pero con un plazo: el suyo
     * espera lo que haga falta, y un servidor gratuito caído no puede dejar
     * colgada la firma de nadie.
     */
    static final class TimeoutTsaClient extends TSAClientBouncyCastle {

        private static final HttpClient HTTP = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        TimeoutTsaClient(String url) {
            super(url, null, null, 8192);
            setDigestName("SHA-256");
        }

        @Override
        protected byte[] getTSAResponse(byte[] request) throws Exception {
            HttpResponse<byte[]> response = HTTP.send(HttpRequest.newBuilder(URI.create(tsaURL))
                            .timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "application/timestamp-query")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(request))
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("respondió " + response.statusCode());
            }
            return response.body();
        }
    }

    // --- Comprobación -------------------------------------------------------------

    /**
     * Si un PDF lleva un sello válido que cubre el documento entero: lo que
     * diría el panel de firmas de un lector de PDF, sin contar con que conozca
     * al certificado.
     */
    public static boolean verify(byte[] pdf) {
        try {
            AcroFields fields = new PdfReader(pdf).getAcroFields();
            List<String> names = fields.getSignedFieldNames();
            if (names.isEmpty()) return false;
            for (String name : names) {
                if (!fields.signatureCoversWholeDocument(name)) return false;
                if (!fields.verifySignature(name).verify()) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
