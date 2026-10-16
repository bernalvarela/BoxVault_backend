package com.storagemanager.storage_management.service.signing;

import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.AppUser;
import com.storagemanager.storage_management.model.ContractSigner;
import com.storagemanager.storage_management.model.ContractSigning;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.enums.SigningKind;
import com.storagemanager.storage_management.model.enums.SigningStatus;
import com.storagemanager.storage_management.repository.AppUserRepository;
import com.storagemanager.storage_management.repository.ContractSigningRepository;
import com.storagemanager.storage_management.service.DocumentService;
import com.storagemanager.storage_management.service.MailSettingsService;
import com.storagemanager.storage_management.service.RentalAgreementService;
import com.storagemanager.storage_management.service.pdf.Pdfs;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Manda el contrato firmado por correo a todos: a cada firmante que tenga correo
 * en su ficha, a quien preparó la firma y, si se configuró, a una dirección que
 * recibe siempre copia ({@code boxvault.signing.mail-copy-to}).
 * <p>
 * Un correo por persona y no uno con todos en copia: nadie ve la dirección de
 * los demás. Cada envío, bien o mal, queda en el registro de la firma.
 * <p>
 * Sale solo cuando se completa una firma, después de guardarla y en otro hilo:
 * si el servidor de correo tarda o está caído, la firma ya está hecha y sellada
 * igual. Desde la pestaña Firma se puede volver a mandar.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SignedContractMailer {

    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    private static final DateTimeFormatter DAY_TIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy 'a las' HH:mm", Pdfs.ES).withZone(MADRID);

    private final ContractSigningRepository signings;
    private final AppUserRepository users;
    private final DocumentService documents;
    private final RentalAgreementService rentals;
    private final ContractSigningService signingService;
    private final MailSettingsService mailSettings;

    /** A quién se manda: su correo, cómo saludarle y, si firma, quién es. */
    public record Recipient(String email, String greetingName, Long signerId) {}

    /** Lo que pasó al mandar: cuántos salieron, cuántos no y por qué. */
    public record Result(int sent, int failed, List<String> problems) {}

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSigned(ContractSigningService.SignedContract event) {
        try {
            Result result = send(event.signingId());
            log.info("Contrato firmado de la firma {} enviado por correo: {} bien, {} mal",
                    event.signingId(), result.sent(), result.failed());
        } catch (RuntimeException e) {
            log.warn("No se pudo mandar por correo el contrato firmado de la firma {}", event.signingId(), e);
        }
    }

    /** Vuelve a mandarlo, desde la pestaña Firma. */
    public Result resend(Long rentalId, Long signingId) {
        rentals.getAgreementById(rentalId);
        ContractSigning signing = signings.findById(signingId)
                .filter(s -> s.getRentalAgreement().getId().equals(rentalId))
                .orElseThrow(() -> new ResourceNotFoundException("No hay ninguna firma " + signingId + " en este contrato"));
        return send(signing.getId());
    }

    Result send(Long signingId) {
        ContractSigning signing = signings.findById(signingId)
                .orElseThrow(() -> new ResourceNotFoundException("No hay ninguna firma " + signingId));
        if (signing.getStatus() != SigningStatus.COMPLETADA || signing.getSignedDocument() == null) {
            throw new BadRequestException("El contrato todavía no está firmado por todos");
        }

        Optional<MailSettingsService.Configured> configured = mailSettings.sender();
        String preparer = users.findById(signing.getPreparedByUserId()).map(AppUser::getEmail).orElse(null);
        List<Recipient> recipients = recipientsOf(signing, preparer,
                configured.map(c -> String.join(",", c.copyTo())).orElse(null));
        if (recipients.isEmpty()) {
            signingService.logMail(signingId, null, false, "Nadie tiene correo en su ficha: no se mandó a nadie");
            return new Result(0, 0, List.of("Nadie tiene correo en su ficha"));
        }
        if (configured.isEmpty()) {
            String why = "El correo no está configurado (Administración › Configuración)";
            signingService.logMail(signingId, null, false, why + ": no se mandó a "
                    + String.join(", ", recipients.stream().map(Recipient::email).toList()));
            return new Result(0, recipients.size(), List.of(why));
        }
        JavaMailSender sender = configured.get().sender();
        InternetAddress from = configured.get().from();

        byte[] pdf = read(documents.open(signing.getSignedDocument()).stream());
        String fileName = signing.getSignedDocument().getFileName();
        String subject = (signing.getKind() == SigningKind.SALIDA ? "Contrato de salida firmado: " : "Contrato firmado: ")
                + signing.getRentalAgreement().getAgreementNumber() + unitOf(signing.getRentalAgreement());

        int sent = 0;
        List<String> problems = new ArrayList<>();
        for (Recipient recipient : recipients) {
            try {
                MimeMessage message = sender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
                helper.setFrom(from);
                if (!blank(preparer)) helper.setReplyTo(preparer);
                helper.setTo(recipient.email());
                helper.setSubject(subject);
                helper.setText(body(signing, recipient), false);
                helper.addAttachment(fileName, new ByteArrayResource(pdf), "application/pdf");
                sender.send(message);
                sent++;
                signingService.logMail(signingId, recipient.signerId(), true, "A " + recipient.email());
            } catch (Exception e) {
                String problem = recipient.email() + ": " + rootMessage(e);
                problems.add(problem);
                log.warn("No se pudo mandar el contrato firmado a {}", recipient.email(), e);
                signingService.logMail(signingId, recipient.signerId(), false, "A " + problem);
            }
        }
        return new Result(sent, problems.size(), problems);
    }

    /**
     * Los destinatarios, sin repetir: primero los firmantes, en su orden, luego
     * quien preparó la firma y la dirección de copia. Un correo que se repite
     * (el propietario que además preparó la firma) sale una sola vez, como
     * firmante.
     */
    public static List<Recipient> recipientsOf(ContractSigning signing, String preparerEmail, String copyTo) {
        Map<String, Recipient> byEmail = new LinkedHashMap<>();
        for (ContractSigner signer : signing.getSigners()) {
            String email = clean(signer.getEmail());
            if (email != null) {
                byEmail.putIfAbsent(email.toLowerCase(Locale.ROOT),
                        new Recipient(email, signer.getFullName().split("\\s+")[0], signer.getId()));
            }
        }
        List<String> others = new ArrayList<>();
        others.add(preparerEmail);
        if (copyTo != null) others.addAll(List.of(copyTo.split("[,;\\s]+")));
        for (String other : others) {
            String email = clean(other);
            if (email != null) byEmail.putIfAbsent(email.toLowerCase(Locale.ROOT), new Recipient(email, null, null));
        }
        return List.copyOf(byEmail.values());
    }

    private static String body(ContractSigning signing, Recipient recipient) {
        RentalAgreement rental = signing.getRentalAgreement();
        boolean exit = signing.getKind() == SigningKind.SALIDA;
        StringBuilder text = new StringBuilder();
        text.append(recipient.greetingName() == null ? "Hola," : "Hola, " + recipient.greetingName() + ":").append("\n\n");
        text.append("Ya han firmado todas las partes el ")
                .append(exit ? "contrato de salida " : "contrato ")
                .append(rental.getAgreementNumber()).append(unitOf(rental))
                .append(". Te mandamos adjunta ")
                .append(recipient.signerId() == null ? "una copia" : "tu copia")
                .append(" firmada en PDF.\n\nFirmaron:\n");
        for (ContractSigner signer : signing.getSigners()) {
            text.append("- ").append(signer.getFullName());
            if (signer.getOnBehalfOf() != null) text.append(" (en nombre de ").append(signer.getOnBehalfOf()).append(")");
            if (signer.getSignedAt() != null) text.append(", el ").append(DAY_TIME.format(signer.getSignedAt()));
            text.append('\n');
        }
        text.append("\nGuarda este correo. El PDF lleva un sello que permite comprobar que no ha cambiado desde que ")
                .append("se firmó: en Adobe Reader se ve en el panel de firmas. Al final del documento está la hoja ")
                .append("de evidencias con los datos de cada firma.\n\n");
        text.append("Huella del contrato que se firmó (SHA-256):\n").append(signing.getOriginalSha256()).append("\n");
        return text.toString();
    }

    private static String unitOf(RentalAgreement rental) {
        return rental.getStorageUnit() == null || rental.getStorageUnit().getName() == null
                ? "" : " (" + rental.getStorageUnit().getName() + ")";
    }

    /** Un correo que parece un correo, o null. */
    private static String clean(String email) {
        if (email == null) return null;
        String value = email.trim();
        return value.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+") ? value : null;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    private static byte[] read(InputStream stream) {
        try (InputStream in = stream) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el contrato firmado", e);
        }
    }
}
