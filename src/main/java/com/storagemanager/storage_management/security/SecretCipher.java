package com.storagemanager.storage_management.security;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Cifra los secretos que la aplicación tiene que guardar en la base de datos
 * para poder usarlos después, como la contraseña del servidor de correo.
 * <p>
 * AES-GCM con una clave que sale del secreto de la sesión
 * (BOXVAULT_JWT_SECRET), que ya vive fuera de la base de datos. Así una copia
 * de la base, sola, no da la contraseña. A cambio, si se cambia ese secreto
 * hay que volver a escribir la contraseña del correo en la configuración.
 */
@Component
public class SecretCipher {

    private static final String PURPOSE = "boxvault-secretos-guardados|";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecurityProperties properties;

    public SecretCipher(SecurityProperties properties) {
        this.properties = properties;
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length)
                    .put(iv).put(encrypted).array());
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo cifrar el secreto", e);
        }
    }

    public String decrypt(String stored) {
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, all, 0, 12));
            return new String(cipher.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo descifrar la contraseña guardada. Si ha cambiado "
                    + "BOXVAULT_JWT_SECRET, vuelve a escribirla en la configuración.", e);
        }
    }

    private SecretKeySpec key() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                (PURPOSE + new String(properties.secretBytes(), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, "AES");
    }
}
