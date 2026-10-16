package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.dto.MailSettingsDTOs;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.model.MailSettings;
import com.storagemanager.storage_management.model.enums.MailSecurity;
import com.storagemanager.storage_management.repository.MailSettingsRepository;
import com.storagemanager.storage_management.security.SecretCipher;
import jakarta.mail.internet.InternetAddress;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * El servidor de correo saliente, configurado desde la aplicación y no desde
 * el entorno: se cambia sin tocar el servidor ni reiniciar nada.
 * <p>
 * Quien manda correo pide {@link #sender()}: si el correo está apagado o le
 * falta algo, no hay remitente y no se manda nada.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailSettingsService {

    private static final String EMAIL = "[^@\\s]+@[^@\\s]+\\.[^@\\s]+";

    private final MailSettingsRepository repository;
    private final SecretCipher cipher;

    /** Un remitente listo para usar: con qué se manda, en nombre de quién y a quién va copia. */
    public record Configured(JavaMailSender sender, InternetAddress from, List<String> copyTo) {}

    @Transactional(readOnly = true)
    public MailSettingsDTOs.View get() {
        return view(current());
    }

    @Transactional
    public MailSettingsDTOs.View update(MailSettingsDTOs.Update request) {
        MailSettings settings = current();
        String host = trim(request.host());
        int port = request.port() == null ? 587 : request.port();
        String from = trim(request.fromAddress());
        if (port < 1 || port > 65535) throw new BadRequestException("El puerto tiene que estar entre 1 y 65535");
        if (from != null && !from.matches(EMAIL)) throw new BadRequestException("El remitente no es un correo válido");
        List<String> copies = copies(request.copyTo());
        for (String copy : copies) {
            if (!copy.matches(EMAIL)) throw new BadRequestException("«" + copy + "» no es un correo válido");
        }
        if (request.enabled()) {
            if (host == null) throw new BadRequestException("Para mandar correo hace falta el servidor (host)");
            if (from == null && trim(request.username()) == null) {
                throw new BadRequestException("Para mandar correo hace falta el remitente");
            }
        }

        settings.setEnabled(request.enabled());
        settings.setHost(host);
        settings.setPort(port);
        settings.setSecurity(request.security() == null ? MailSecurity.STARTTLS : request.security());
        settings.setUsername(trim(request.username()));
        if (request.clearPassword()) {
            settings.setPasswordEncrypted(null);
        } else if (request.password() != null && !request.password().isEmpty()) {
            settings.setPasswordEncrypted(cipher.encrypt(request.password()));
        }
        settings.setFromAddress(from);
        settings.setFromName(trim(request.fromName()));
        settings.setCopyTo(copies.isEmpty() ? null : String.join(", ", copies));
        MailSettings saved = repository.save(settings);
        log.info("Configuración del correo guardada: {} ({}:{}, {})",
                Boolean.TRUE.equals(saved.getEnabled()) ? "activado" : "apagado", saved.getHost(), saved.getPort(),
                saved.getSecurity());
        return view(saved);
    }

    /** Manda un correo de prueba con lo guardado. Si falla, dice por qué, tal como responde el servidor. */
    @Transactional(readOnly = true)
    public MailSettingsDTOs.TestResult test(String to) {
        String address = trim(to);
        if (address == null || !address.matches(EMAIL)) throw new BadRequestException("Escribe un correo válido para la prueba");
        Configured configured = sender().orElseThrow(() -> new BadRequestException(
                "El correo está apagado o le falta el servidor o el remitente: guárdalo activado antes de probar"));
        try {
            var message = configured.sender().createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(configured.from());
            helper.setTo(address);
            helper.setSubject("Prueba del correo de BoxVault");
            helper.setText("Si lees esto, BoxVault ya puede mandar correo: los contratos firmados llegarán a "
                    + "cada firmante desde esta dirección.");
            configured.sender().send(message);
            return new MailSettingsDTOs.TestResult(true, "Correo de prueba enviado a " + address);
        } catch (Exception e) {
            log.warn("Falló el correo de prueba a {}", address, e);
            return new MailSettingsDTOs.TestResult(false, "No se pudo mandar: " + rootMessage(e));
        }
    }

    /** El remitente configurado, o vacío si el correo está apagado o incompleto. */
    @Transactional(readOnly = true)
    public Optional<Configured> sender() {
        MailSettings settings = current();
        if (!Boolean.TRUE.equals(settings.getEnabled()) || settings.getHost() == null) return Optional.empty();
        String fromAddress = settings.getFromAddress() != null ? settings.getFromAddress() : settings.getUsername();
        if (fromAddress == null) return Optional.empty();

        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(settings.getHost());
        sender.setPort(settings.getPort());
        sender.setDefaultEncoding("UTF-8");
        Properties props = sender.getJavaMailProperties();
        boolean auth = settings.getUsername() != null;
        if (auth) {
            sender.setUsername(settings.getUsername());
            if (settings.getPasswordEncrypted() != null) {
                sender.setPassword(cipher.decrypt(settings.getPasswordEncrypted()));
            }
        }
        props.put("mail.smtp.auth", String.valueOf(auth));
        switch (settings.getSecurity()) {
            case STARTTLS -> {
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.starttls.required", "true");
            }
            case SSL -> props.put("mail.smtp.ssl.enable", "true");
            case NINGUNA -> { }
        }
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "20000");
        props.put("mail.smtp.writetimeout", "20000");

        try {
            InternetAddress from = settings.getFromName() == null
                    ? new InternetAddress(fromAddress)
                    : new InternetAddress(fromAddress, settings.getFromName(), StandardCharsets.UTF_8.name());
            return Optional.of(new Configured(sender, from, copies(settings.getCopyTo())));
        } catch (Exception e) {
            log.warn("El remitente del correo no es válido: {}", fromAddress, e);
            return Optional.empty();
        }
    }

    private MailSettings current() {
        return repository.findById(MailSettings.SINGLETON)
                .orElseGet(() -> MailSettings.builder().id(MailSettings.SINGLETON).build());
    }

    private static MailSettingsDTOs.View view(MailSettings s) {
        return new MailSettingsDTOs.View(Boolean.TRUE.equals(s.getEnabled()), s.getHost(), s.getPort(), s.getSecurity(),
                s.getUsername(), s.getPasswordEncrypted() != null, s.getFromAddress(), s.getFromName(), s.getCopyTo(),
                s.getUpdatedAt(), s.getUpdatedBy());
    }

    private static List<String> copies(String text) {
        if (text == null || text.isBlank()) return List.of();
        return Arrays.stream(text.split("[,;\\s]+")).map(String::trim).filter(v -> !v.isEmpty()).toList();
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
