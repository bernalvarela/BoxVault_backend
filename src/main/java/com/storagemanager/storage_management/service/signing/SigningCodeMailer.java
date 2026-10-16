package com.storagemanager.storage_management.service.signing;

import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.service.MailSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Manda el código para firmar con enlace al correo del firmante. Se manda en el
 * momento, no en otro hilo: quien lo pide está esperándolo con el móvil en la
 * mano, y si no sale tiene que saberlo ya.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SigningCodeMailer {

    private final MailSettingsService mailSettings;

    /** Si hay correo configurado: sin él no se puede firmar con enlace. */
    public boolean available() {
        return mailSettings.sender().isPresent();
    }

    public void send(String email, String name, String contractTitle, String code, int validMinutes) {
        MailSettingsService.Configured configured = mailSettings.sender().orElseThrow(() ->
                new BadRequestException("El correo de BoxVault no está configurado: no se puede mandar el código"));
        try {
            var message = configured.sender().createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(configured.from());
            helper.setTo(email);
            helper.setSubject(code + " es tu código para firmar el contrato");
            helper.setText("Hola, " + name.split("\\s+")[0] + ":\n\n"
                    + "Tu código para leer y firmar el " + contractTitle.toLowerCase(java.util.Locale.ROOT)
                            .replace("contrato de arrendamiento", "contrato") + " es:\n\n"
                    + "    " + code + "\n\n"
                    + "Vale " + validMinutes + " minutos. Tecléalo en la página del enlace que te enviaron.\n\n"
                    + "Si no has pedido firmar ningún contrato, no hagas nada: sin este código nadie puede "
                    + "ver ni firmar el contrato en tu nombre.\n");
            configured.sender().send(message);
        } catch (Exception e) {
            log.warn("No se pudo mandar el código para firmar a {}", email, e);
            throw new BadRequestException("No se pudo mandar el código a tu correo. Inténtalo dentro de un momento "
                    + "o avisa a quien te envió el enlace.");
        }
    }
}
