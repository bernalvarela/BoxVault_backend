package com.storagemanager.storage_management.service.signing;

import com.storagemanager.storage_management.config.SigningProperties;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.service.storage.FileStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * El certificado con el que BoxVault sella los contratos firmados.
 * <p>
 * Es suyo y autofirmado: no lo emite ninguna autoridad, así que Adobe dirá que
 * no conoce al firmante. No pretende identificar a nadie —eso lo hacen la firma
 * de cada uno y su registro—, sino que cualquier cambio en el PDF después de
 * sellarlo se note: el sello deja de cuadrar.
 * <p>
 * Se crea solo la primera vez que hace falta y se guarda en el almacén de
 * ficheros ({@value #KEY}), protegido con la contraseña de la configuración.
 * Se guarda y no se regenera en cada arranque porque todos los contratos
 * sellados tienen que poder comprobarse con el mismo certificado.
 * <p>
 * Todo con la criptografía del JDK: BouncyCastle sólo construye el certificado,
 * sin registrar su proveedor, que en la imagen nativa es lo que da problemas.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SigningKeyStore {

    static final String KEY = "firma/boxvault-sello.p12";
    private static final String ALIAS = "sello";
    private static final String SUBJECT = "CN=BoxVault - Pasaxe 29, O=Comunidad de bienes Pasaxe 29, C=ES";

    private final FileStorage storage;
    private final SigningProperties properties;

    /** La clave y su cadena (un único certificado). */
    public record Credentials(PrivateKey key, Certificate[] chain) {
        public X509Certificate certificate() {
            return (X509Certificate) chain[0];
        }
    }

    private volatile Credentials cached;

    public Credentials credentials() {
        Credentials current = cached;
        if (current != null) return current;
        synchronized (this) {
            if (cached == null) cached = loadOrCreate();
            return cached;
        }
    }

    private Credentials loadOrCreate() {
        char[] password = password();
        byte[] stored = read();
        try {
            if (stored != null) {
                KeyStore keyStore = KeyStore.getInstance("PKCS12");
                keyStore.load(new ByteArrayInputStream(stored), password);
                return new Credentials((PrivateKey) keyStore.getKey(ALIAS, password), keyStore.getCertificateChain(ALIAS));
            }
            Credentials created = create();
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(null, password);
            keyStore.setKeyEntry(ALIAS, created.key(), password, created.chain());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            keyStore.store(out, password);
            byte[] bytes = out.toByteArray();
            storage.put(KEY, "application/x-pkcs12", bytes.length, new ByteArrayInputStream(bytes));
            log.info("Creado el certificado del sello de los contratos firmados ({}), válido hasta {}",
                    SUBJECT, created.certificate().getNotAfter());
            return created;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("No se pudo abrir el certificado del sello: "
                    + "¿ha cambiado BOXVAULT_SIGNING_PASSWORD?", e);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo preparar el certificado del sello", e);
        }
    }

    private static Credentials create() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072, new SecureRandom());
        KeyPair pair = generator.generateKeyPair();

        Instant now = Instant.now();
        X500Name subject = new X500Name(SUBJECT);
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject,
                new BigInteger(64, new SecureRandom()),
                Date.from(now.minus(Duration.ofDays(1))),
                Date.from(now.plus(Duration.ofDays(3650))),
                subject,
                pair.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.nonRepudiation));

        X509Certificate certificate = new JcaX509CertificateConverter()
                .getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate())));
        return new Credentials(pair.getPrivate(), new Certificate[]{certificate});
    }

    /**
     * El almacén guardado, o null si no existe todavía.
     * <p>
     * Sólo "no existe" da null. Cualquier otro fallo —el almacén caído un
     * momento— se propaga: tomarlo por "no existe" crearía un certificado nuevo
     * encima del bueno, y los contratos ya sellados dejarían de cuadrar con él.
     */
    private byte[] read() {
        try (InputStream in = storage.open(KEY)) {
            return in.readAllBytes();
        } catch (Exception e) {
            if (isMissing(e)) return null;
            throw new IllegalStateException("No se pudo leer el certificado del sello del almacén de ficheros", e);
        }
    }

    /** Si el fallo es que el objeto no está (disco o S3), mirando toda la cadena de causas. */
    private static boolean isMissing(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            String name = t.getClass().getSimpleName();
            if (name.equals("NoSuchFileException") || name.equals("NoSuchKeyException")
                    || name.equals("FileNotFoundException")) {
                return true;
            }
        }
        return false;
    }

    private char[] password() {
        String password = properties.getKeystorePassword();
        if (password == null || password.isBlank()) {
            throw new BadRequestException("Falta la contraseña del sello de los contratos firmados "
                    + "(BOXVAULT_SIGNING_PASSWORD): sin ella no se puede sellar. Las firmas quedan guardadas.");
        }
        return password.toCharArray();
    }
}
