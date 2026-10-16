package com.storagemanager.storage_management;

import com.storagemanager.storage_management.dto.MailSettingsDTOs;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.model.ContractSigner;
import com.storagemanager.storage_management.model.ContractSigning;
import com.storagemanager.storage_management.model.MailSettings;
import com.storagemanager.storage_management.model.enums.MailSecurity;
import com.storagemanager.storage_management.repository.MailSettingsRepository;
import com.storagemanager.storage_management.security.SecretCipher;
import com.storagemanager.storage_management.service.MailSettingsService;
import com.storagemanager.storage_management.service.signing.SignedContractMailer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * El correo saliente configurado desde la aplicación: la contraseña se guarda
 * cifrada y no vuelve nunca a la pantalla, apagado no hay remitente, y el
 * contrato firmado va a cada uno una sola vez.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class MailSettingsTest {

    @Autowired private MailSettingsService settings;
    @Autowired private MailSettingsRepository repository;
    @Autowired private SecretCipher cipher;

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
    }

    private static MailSettingsDTOs.Update update(boolean enabled, String password, boolean clear) {
        return new MailSettingsDTOs.Update(enabled, "smtp.ejemplo.es", 587, MailSecurity.STARTTLS,
                "pasaxe29@ejemplo.es", password, clear, null, "Pasaxe 29", "copia@ejemplo.es, otra@ejemplo.es");
    }

    @Test
    void thePasswordIsStoredEncryptedAndNeverComesBack() {
        MailSettingsDTOs.View view = settings.update(update(true, "secreta-de-aplicacion", false));
        assertTrue(view.hasPassword());

        String stored = repository.findById(MailSettings.SINGLETON).orElseThrow().getPasswordEncrypted();
        assertNotNull(stored);
        assertFalse(stored.contains("secreta"), "en la base no va en claro");
        assertEquals("secreta-de-aplicacion", cipher.decrypt(stored));

        // Guardar sin contraseña conserva la que había; pedir quitarla, la quita.
        assertTrue(settings.update(update(true, null, false)).hasPassword());
        assertFalse(settings.update(update(true, null, true)).hasPassword());
    }

    @Test
    void switchedOffThereIsNoSender() {
        settings.update(update(false, "x", false));
        assertTrue(settings.sender().isEmpty());

        settings.update(update(true, "x", false));
        MailSettingsService.Configured configured = settings.sender().orElseThrow();
        assertEquals("pasaxe29@ejemplo.es", configured.from().getAddress(), "sin remitente, el usuario");
        assertEquals(List.of("copia@ejemplo.es", "otra@ejemplo.es"), configured.copyTo());
    }

    @Test
    void anEnabledServerNeedsAHostAndValidAddresses() {
        assertThrows(BadRequestException.class, () -> settings.update(new MailSettingsDTOs.Update(true, " ", 587,
                MailSecurity.STARTTLS, "a@b.es", null, false, null, null, null)));
        assertThrows(BadRequestException.class, () -> settings.update(new MailSettingsDTOs.Update(false, null, 587,
                MailSecurity.STARTTLS, null, null, false, "no-es-un-correo", null, null)));
    }

    @Test
    void eachPersonGetsTheSignedContractOnce() {
        ContractSigning signing = ContractSigning.builder().build();
        signing.getSigners().add(ContractSigner.builder().id(1L).fullName("Bernal Varela Gómez").email("bernal@ejemplo.es").build());
        signing.getSigners().add(ContractSigner.builder().id(2L).fullName("Ana Gómez Pérez").email("ANA@ejemplo.es").build());
        signing.getSigners().add(ContractSigner.builder().id(3L).fullName("Luis Gómez Pérez").email(null).build());
        signing.getSigners().add(ContractSigner.builder().id(4L).fullName("Carmen Pérez").email("no vale").build());

        List<SignedContractMailer.Recipient> recipients = SignedContractMailer.recipientsOf(
                signing, "Bernal@Ejemplo.es", "ana@ejemplo.es, gestoria@ejemplo.es");

        assertEquals(List.of("bernal@ejemplo.es", "ANA@ejemplo.es", "gestoria@ejemplo.es"),
                recipients.stream().map(SignedContractMailer.Recipient::email).toList());
        assertEquals("Bernal", recipients.get(0).greetingName());
        assertNull(recipients.get(2).signerId(), "la copia no es de ningún firmante");
    }
}
