package com.storagemanager.storage_management.service.signing;

import com.storagemanager.storage_management.config.InvoicingProperties;
import com.storagemanager.storage_management.config.SigningProperties;
import com.storagemanager.storage_management.dto.SigningDTOs;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.AppUser;
import com.storagemanager.storage_management.model.Client;
import com.storagemanager.storage_management.model.ContractSigner;
import com.storagemanager.storage_management.model.ContractSigning;
import com.storagemanager.storage_management.model.Document;
import com.storagemanager.storage_management.model.Owner;
import com.storagemanager.storage_management.model.OwnerMembership;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.SigningEvent;
import com.storagemanager.storage_management.model.enums.AccessArea;
import com.storagemanager.storage_management.model.enums.AccessLevel;
import com.storagemanager.storage_management.model.enums.DocumentType;
import com.storagemanager.storage_management.model.enums.RentalStatus;
import com.storagemanager.storage_management.model.enums.SignerRole;
import com.storagemanager.storage_management.model.enums.SigningChannel;
import com.storagemanager.storage_management.model.enums.SigningEventType;
import com.storagemanager.storage_management.model.enums.SigningKind;
import com.storagemanager.storage_management.model.enums.SigningStatus;
import com.storagemanager.storage_management.repository.AppUserRepository;
import com.storagemanager.storage_management.repository.ContractSigningRepository;
import com.storagemanager.storage_management.repository.ContractSignerRepository;
import com.storagemanager.storage_management.repository.OwnerMembershipRepository;
import com.storagemanager.storage_management.repository.SigningEventRepository;
import com.storagemanager.storage_management.security.Authenticated;
import com.storagemanager.storage_management.security.CurrentUser;
import com.storagemanager.storage_management.service.ContractService;
import com.storagemanager.storage_management.service.DocumentService;
import com.storagemanager.storage_management.service.InvoiceIssuer;
import com.storagemanager.storage_management.service.RentalAgreementService;
import com.storagemanager.storage_management.service.RentalDocumentService;
import com.storagemanager.storage_management.service.pdf.Pdfs;
import com.storagemanager.storage_management.service.storage.FileStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * La firma electrónica de los contratos, de principio a fin y sin depender de
 * nadie de fuera.
 * <ol>
 *   <li><b>Preparar.</b> Se compone el contrato una última vez y se congela: su
 *       huella SHA-256 es lo que firma cada uno. Se apunta quién tiene que
 *       firmar —los propietarios que arriendan (o el comunero que firma por la
 *       comunidad de bienes), los arrendatarios y los fiadores—, con su nombre y
 *       su NIF de ese momento.</li>
 *   <li><b>Firmar.</b> En persona, en la tableta de quien gestiona, que deja
 *       anotado que comprobó el DNI; o con un enlace personal en el móvil de
 *       cada uno, que pide un código de seis cifras mandado a su correo o
 *       entregado por quien gestiona.
 *       De cada firma queda la imagen, cuándo, desde qué IP y dispositivo y la
 *       huella del contrato que tenía delante.</li>
 *   <li><b>Sellar.</b> Con la última firma, las firmas se estampan en el PDF,
 *       se añade la hoja de evidencias y se sella (ver {@link SignatureSealService}).
 *       El PDF firmado se archiva en el alquiler.</li>
 * </ol>
 * Todo lo que pasa queda en un registro encadenado ({@link SigningEvent}).
 * <p>
 * Es una firma electrónica simple: vale como cualquier contrato firmado, pero
 * si alguien niega haber firmado, probarlo le toca a quien presenta el
 * contrato. Por eso se guarda todo lo que ayuda a probarlo.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractSigningService {

    /** Códigos incorrectos que admite un enlace antes de dejar de valer. */
    static final int MAX_ATTEMPTS = 5;

    /** Lo más que puede pesar la imagen de una firma. */
    private static final int MAX_SIGNATURE_BYTES = 600 * 1024;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    private static final DateTimeFormatter DAY_TIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Pdfs.ES).withZone(MADRID);

    /** Quien actúa con un enlace, en el registro: no es un usuario de BoxVault. */
    static final String SIGNER_ACTOR = "firmante";

    private final ContractSigningRepository signings;
    private final ContractSignerRepository signers;
    private final SigningEventRepository events;
    private final RentalAgreementService rentals;
    private final ContractService contracts;
    private final InvoiceIssuer issuers;
    private final OwnerMembershipRepository memberships;
    private final DocumentService documents;
    private final RentalDocumentService rentalDocuments;
    private final FileStorage storage;
    private final SignatureSealService sealer;
    private final SigningProperties properties;
    private final AppUserRepository users;
    private final ApplicationEventPublisher publisher;
    private final SigningCodeMailer codeMailer;

    /** De dónde llega una petición, para el registro. */
    public record Origin(String ip, String userAgent) {}

    // ==================================================================
    // Desde BoxVault, con sesión
    // ==================================================================

    @Transactional(readOnly = true)
    public SigningDTOs.Overview overview(Long rentalId) {
        RentalAgreement rental = rentals.getAgreementById(rentalId);
        InvoiceIssuer.Issuer issuer = issuers.forUnit(rental.getStorageUnit());
        Owner entity = soleEntity(issuer);
        List<SigningDTOs.SigningView> views = signings.findByRentalAgreementIdOrderByIdDesc(rentalId).stream()
                .map(this::view)
                .toList();
        boolean exitAllowed = rental.getStatus() != RentalStatus.ACTIVE || rental.isTerminationScheduled();
        return new SigningDTOs.Overview(views, onBehalfOptions(entity),
                entity == null ? null : entity.getFullName(), exitAllowed);
    }

    /**
     * Congela el contrato y crea la lista de firmantes.
     */
    @Transactional
    public SigningDTOs.SigningView prepare(Long rentalId, SigningDTOs.PrepareRequest request, Origin origin) {
        RentalAgreement rental = rentals.getAgreementById(rentalId);
        SigningKind kind = request.kind() == null ? SigningKind.ENTRADA : request.kind();
        InvoiceIssuer.Issuer issuer = issuers.forUnit(rental.getStorageUnit());

        Owner onBehalf = null;
        Owner entity = soleEntity(issuer);
        if (entity != null && !onBehalfOptions(entity).isEmpty()) {
            if (request.onBehalfOwnerId() == null) {
                throw new BadRequestException("Elige qué comunero firma en nombre de " + entity.getFullName());
            }
            onBehalf = memberships.findByEntityId(entity.getId()).stream()
                    .map(OwnerMembership::getMember)
                    .filter(member -> member.getId().equals(request.onBehalfOwnerId()))
                    .findFirst()
                    .orElseThrow(() -> new BadRequestException("Ese propietario no es comunero de " + entity.getFullName()));
        }

        ContractService.ForSigning frozen = contracts.generateForSigning(rentalId, kind, onBehalf);
        String sha256 = sha256Hex(frozen.content());

        ContractSigning signing = ContractSigning.builder()
                .rentalAgreement(rental)
                .kind(kind)
                .originalDocument(frozen.document())
                .originalSha256(sha256)
                .slots(SigningSlots.encode(frozen.slots()))
                .preparedByUserId(Authenticated.user().id())
                .build();
        for (ContractSigner signer : signersFor(rental, issuer, onBehalf)) {
            signer.setSigning(signing);
            signing.getSigners().add(signer);
        }
        ContractSigning saved = signings.saveAndFlush(signing);

        event(saved, null, SigningEventType.PREPARADA, currentActor(), origin,
                "Contrato congelado: " + frozen.document().getFileName() + " · SHA-256 " + sha256
                        + " · " + saved.getSigners().size() + " firmante(s)");
        log.info("Preparada la firma {} del contrato {} ({} firmantes)",
                saved.getId(), rental.getAgreementNumber(), saved.getSigners().size());
        return view(saved);
    }

    @Transactional
    public SigningDTOs.SigningView cancel(Long rentalId, Long signingId, String reason, Origin origin) {
        ContractSigning signing = lockOwned(rentalId, signingId);
        requireInProgress(signing);
        Instant now = now();
        signing.setStatus(SigningStatus.CANCELADA);
        signing.setCancelledAt(now);
        signing.setCancelReason(trim(reason, 255));
        // Los enlaces que hubiera mueren con ella.
        signing.getSigners().forEach(signer -> {
            if (signer.getTokenExpiresAt() != null && signer.getTokenExpiresAt().isAfter(now)) {
                signer.setTokenExpiresAt(now);
            }
        });
        signings.save(signing);
        event(signing, null, SigningEventType.CANCELADA, currentActor(), origin,
                reason == null || reason.isBlank() ? null : trim(reason, 255));
        return view(signing);
    }

    /**
     * El enlace de un firmante para firmar desde su móvil. Pedir otro anula el
     * anterior: sólo vale el último.
     */
    @Transactional
    public SigningDTOs.LinkView link(Long rentalId, Long signingId, Long signerId, String origin, Origin from) {
        ContractSigning signing = lockOwned(rentalId, signingId);
        requireInProgress(signing);
        ContractSigner signer = signerOf(signing, signerId);
        if (signer.hasSigned()) {
            throw new BadRequestException(signer.getFullName() + " ya ha firmado");
        }
        // Con el enlace, antes de ver el contrato hay que teclear un código: o se
        // lo pide él a su correo, o se lo hace llegar quien gestiona por otro
        // camino (ver manualCode). Por eso el enlace no exige correo.
        String base = baseUrl(origin);

        String token = newToken();
        Instant now = now();
        signer.setTokenHash(sha256Hex(token.getBytes(StandardCharsets.UTF_8)));
        signer.setTokenCreatedAt(now);
        signer.setTokenExpiresAt(now.plus(properties.getLinkTtl()).truncatedTo(ChronoUnit.MILLIS));
        signer.setFailedAttempts(0);
        signer.setLinkOpenedAt(null);
        signer.setCodeHash(null);
        signer.setCodeSentAt(null);
        signer.setCodeExpiresAt(null);
        signer.setCodeSends(0);
        signings.save(signing);
        event(signing, signer, SigningEventType.ENLACE_CREADO, currentActor(), from,
                "Caduca el " + DAY_TIME.format(signer.getTokenExpiresAt()));

        String url = base + "/#/firmar?t=" + token;
        return new SigningDTOs.LinkView(url, signer.getTokenExpiresAt(), whatsappUrl(signer, signing, url));
    }

    /**
     * Firma en persona, en la tableta de quien gestiona.
     *
     * @param identityChecked si quien gestiona ha comprobado su DNI delante de él
     */
    @Transactional
    public SigningDTOs.SigningView signInPerson(Long rentalId, Long signingId, Long signerId,
                                                 byte[] signature, boolean identityChecked, Origin origin) {
        ContractSigning signing = lockOwned(rentalId, signingId);
        requireInProgress(signing);
        ContractSigner signer = signerOf(signing, signerId);
        if (signer.hasSigned()) {
            throw new BadRequestException(signer.getFullName() + " ya ha firmado");
        }
        requirePng(signature);

        String user = currentActor();
        keepSignature(signing, signer, signature, SigningChannel.PRESENCIAL, origin);
        signer.setIdentityChecked(identityChecked);
        signer.setIdentityCheckedBy(identityChecked ? user : null);
        signer.setWitnessedBy(user);
        // Un enlace pendiente ya no hace falta.
        if (signer.getTokenExpiresAt() != null) signer.setTokenExpiresAt(signer.getSignedAt());
        signings.save(signing);
        event(signing, signer, SigningEventType.FIRMADO, user, origin,
                "En persona" + (identityChecked ? ", identidad comprobada con el DNI" : ", sin comprobar el DNI"));

        completeIfReady(signing, origin);
        return view(signing);
    }

    /** Vuelve a intentar sellar una firma completa que no se pudo sellar. */
    @Transactional
    public SigningDTOs.SigningView retrySeal(Long rentalId, Long signingId, Origin origin) {
        ContractSigning signing = lockOwned(rentalId, signingId);
        requireInProgress(signing);
        if (!signing.allSigned()) {
            throw new BadRequestException("Todavía faltan firmas");
        }
        seal(signing, origin);
        return view(signing);
    }

    // ==================================================================
    // Desde el móvil de quien firma, sólo con su enlace
    // ==================================================================

    @Transactional
    public SigningDTOs.PublicInfo publicInfo(String token, Origin origin) {
        ContractSigner found = byToken(token, true);
        ContractSigning signing = lock(found.getSigning().getId());
        ContractSigner signer = signerOf(signing, found.getId());
        if (!signer.hasSigned() && signer.getLinkOpenedAt() == null && signing.isInProgress()) {
            signer.setLinkOpenedAt(now());
            signings.save(signing);
            event(signing, signer, SigningEventType.ENLACE_ABIERTO, SIGNER_ACTOR, origin, null);
        }
        return publicInfoOf(signing, signer);
    }

    /**
     * Manda al correo de la ficha el código para ver y firmar el contrato. Uno
     * por minuto como mucho, y {@value #MAX_CODE_SENDS} por enlace: un enlace
     * en malas manos no puede servir para llenarle el buzón a nadie.
     */
    @Transactional
    public SigningDTOs.CodeSent publicSendCode(String token, Origin origin) {
        ContractSigner found = byToken(token, false);
        ContractSigning signing = lock(found.getSigning().getId());
        ContractSigner signer = signerOf(signing, found.getId());
        if (!signing.isInProgress() || signer.hasSigned() || signer.getFailedAttempts() >= MAX_ATTEMPTS) throw gone();
        String email = validEmail(signer.getEmail());
        if (email == null) {
            throw new BadRequestException("Tu ficha no tiene correo: pide a quien te envió el enlace que lo añada.");
        }
        Instant now = now();
        if (signer.getCodeSentAt() != null && signer.getCodeSentAt().plus(CODE_RESEND).isAfter(now)) {
            throw new BadRequestException("Ya te hemos mandado un código hace menos de un minuto. Mira tu correo "
                    + "(también la carpeta de spam) o espera un poco para pedir otro.");
        }
        if (signer.getCodeSends() >= MAX_CODE_SENDS) {
            throw new BadRequestException("Has pedido demasiados códigos con este enlace. Pide uno nuevo a quien te lo envió.");
        }

        String code = issueCode(signer, CODE_TTL);
        signer.setCodeSends(signer.getCodeSends() + 1);
        signer.setCodeEmail(email);
        signer.setCodeDeliveredBy(null);
        // Si el correo no sale, no queda nada anotado: se puede pedir otra vez.
        codeMailer.send(email, signer.getFullName(), titleOf(signing), code, (int) CODE_TTL.toMinutes());
        signings.save(signing);
        event(signing, signer, SigningEventType.CODIGO_ENVIADO, SIGNER_ACTOR, origin, "Por correo a " + email);
        return new SigningDTOs.CodeSent(maskEmail(email), signer.getCodeExpiresAt());
    }

    /**
     * Un código para que quien gestiona se lo haga llegar al firmante por el
     * camino que quiera: WhatsApp, SMS, de palabra por teléfono. Se enseña una
     * sola vez; pedir otro anula el anterior. Vale {@link #MANUAL_CODE_TTL}:
     * un mensaje se puede leer horas después.
     * <p>
     * Conviene que no vaya por el mismo sitio que el enlace: si los dos van en
     * el mismo WhatsApp, quien tenga ese móvil tiene las dos cosas.
     */
    @Transactional
    public SigningDTOs.ManualCode manualCode(Long rentalId, Long signingId, Long signerId, Origin from) {
        ContractSigning signing = lockOwned(rentalId, signingId);
        requireInProgress(signing);
        ContractSigner signer = signerOf(signing, signerId);
        if (signer.hasSigned()) throw new BadRequestException(signer.getFullName() + " ya ha firmado");
        if (signer.getTokenHash() == null || signer.getTokenExpiresAt() == null
                || !signer.getTokenExpiresAt().isAfter(Instant.now()) || signer.getFailedAttempts() >= MAX_ATTEMPTS) {
            throw new BadRequestException("Primero crea un enlace para " + signer.getFullName() + ": el código va con él");
        }
        String code = issueCode(signer, MANUAL_CODE_TTL);
        String user = currentActor();
        signer.setCodeEmail(null);
        signer.setCodeDeliveredBy(user);
        signings.save(signing);
        event(signing, signer, SigningEventType.CODIGO_ENVIADO, user, from,
                "Generado por " + user + " para hacérselo llegar por otro medio (WhatsApp, SMS, teléfono)");

        String whatsapp = whatsappTo(signer.getPhone(), "Tu código para ver y firmar el "
                + shortTitle(signing) + " es " + code + ". Vale hasta el "
                + DAY_TIME.format(signer.getCodeExpiresAt()) + ".");
        return new SigningDTOs.ManualCode(code, signer.getCodeExpiresAt(), whatsapp);
    }

    /** Lo que vale un código que se entrega a mano. */
    static final java.time.Duration MANUAL_CODE_TTL = java.time.Duration.ofHours(24);

    /** Un código nuevo de seis cifras: guarda su huella y su caducidad, y devuelve el código en claro. */
    private String issueCode(ContractSigner signer, java.time.Duration ttl) {
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        Instant now = now();
        signer.setCodeHash(codeHash(signer, code));
        signer.setCodeSentAt(now);
        signer.setCodeExpiresAt(now.plus(ttl));
        return code;
    }

    /**
     * El PDF congelado, para leerlo antes de firmar. Pide el código: el
     * contrato lleva DNI, domicilio y renta, y no se enseña a quien sólo tiene
     * el enlace.
     */
    @Transactional(noRollbackFor = BadRequestException.class)
    public DocumentService.Content publicDocument(String token, String code, Origin origin) {
        ContractSigner found = byToken(token, false);
        ContractSigning signing = lock(found.getSigning().getId());
        ContractSigner signer = signerOf(signing, found.getId());
        if (!signing.isInProgress() || signer.hasSigned() || signer.getFailedAttempts() >= MAX_ATTEMPTS) throw gone();
        requireCode(signing, signer, code, origin);
        return documents.open(signing.getOriginalDocument());
    }

    /**
     * Firma con el enlace. Hay que haber marcado que se acepta el contrato y
     * teclear el código que llegó a su correo.
     * <p>
     * Un código incorrecto no deshace nada de lo anotado: el intento cuenta, y
     * por eso este método no se revierte con un BadRequestException.
     */
    @Transactional(noRollbackFor = BadRequestException.class)
    public SigningDTOs.PublicInfo publicSign(String token, byte[] signature, String code, boolean accepted,
                                             Origin origin) {
        ContractSigner found = byToken(token, false);
        ContractSigning signing = lock(found.getSigning().getId());
        ContractSigner signer = signerOf(signing, found.getId());
        if (!signing.isInProgress() || signer.hasSigned() || signer.getFailedAttempts() >= MAX_ATTEMPTS) throw gone();
        if (!accepted) {
            throw new BadRequestException("Marca que has leído el contrato y lo aceptas");
        }
        requirePng(signature);
        requireCode(signing, signer, code, origin);

        asPreparer(signing, () -> {
            keepSignature(signing, signer, signature, SigningChannel.ENLACE, origin);
            signer.setCodeVerifiedAt(signer.getSignedAt());
            // El código ya no sirve para nada más.
            signer.setCodeHash(null);
            signings.save(signing);
            event(signing, signer, SigningEventType.FIRMADO, SIGNER_ACTOR, origin,
                    "Con su enlace, desde su dispositivo; código confirmado, " + codeDelivery(signer));
            completeIfReady(signing, origin);
        });
        return publicInfoOf(signing, signer);
    }

    /** Lo que dura un código desde que se manda. */
    static final java.time.Duration CODE_TTL = java.time.Duration.ofMinutes(15);
    /** Lo que hay que esperar para pedir otro. */
    static final java.time.Duration CODE_RESEND = java.time.Duration.ofSeconds(60);
    /** Códigos que se pueden pedir con un mismo enlace. */
    static final int MAX_CODE_SENDS = 5;

    /**
     * Comprueba el código. Uno equivocado cuenta como intento fallido; al
     * quinto, el enlace se bloquea.
     */
    private void requireCode(ContractSigning signing, ContractSigner signer, String code, Origin origin) {
        if (signer.getCodeHash() == null || signer.getCodeExpiresAt() == null) {
            throw new BadRequestException("Pide el código que te mandamos por correo.");
        }
        if (!signer.getCodeExpiresAt().isAfter(Instant.now())) {
            throw new BadRequestException("El código ha caducado. Pide otro.");
        }
        String typed = code == null ? "" : code.replaceAll("\\D", "");
        if (typed.length() == 6 && MessageDigest.isEqual(
                codeHash(signer, typed).getBytes(StandardCharsets.UTF_8),
                signer.getCodeHash().getBytes(StandardCharsets.UTF_8))) {
            return;
        }
        signer.setFailedAttempts(signer.getFailedAttempts() + 1);
        signings.save(signing);
        event(signing, signer, SigningEventType.CODIGO_INCORRECTO, SIGNER_ACTOR, origin,
                "Intento " + signer.getFailedAttempts() + " de " + MAX_ATTEMPTS);
        int left = MAX_ATTEMPTS - signer.getFailedAttempts();
        if (left <= 0) {
            event(signing, signer, SigningEventType.ENLACE_BLOQUEADO, SIGNER_ACTOR, origin, null);
            throw new BadRequestException("El código no es correcto. El enlace ha dejado de valer: "
                    + "pide uno nuevo a quien te lo envió.");
        }
        throw new BadRequestException("El código no es correcto. Revísalo: "
                + (left == 1 ? "queda 1 intento." : "quedan " + left + " intentos."));
    }

    /** Por dónde le llegó el código, en palabras, para el registro y la hoja de evidencias. */
    static String codeDelivery(ContractSigner signer) {
        if (signer.getCodeEmail() != null) return "mandado por BoxVault a su correo " + signer.getCodeEmail();
        if (signer.getCodeDeliveredBy() != null) {
            return "que le hizo llegar " + signer.getCodeDeliveredBy() + " por otro medio (WhatsApp, SMS o teléfono)";
        }
        return "de origen no anotado";
    }

    /** La huella de un código, atada al firmante: el mismo código de otro no vale. */
    private static String codeHash(ContractSigner signer, String code) {
        return sha256Hex((signer.getId() + ":" + code).getBytes(StandardCharsets.UTF_8));
    }

    /** "a***@gmail.com": lo justo para que sepa a qué correo mirar. */
    static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) return "***" + email.substring(Math.max(at, 0));
        return email.charAt(0) + "***" + email.substring(at);
    }

    private static String validEmail(String email) {
        if (email == null) return null;
        String value = email.trim();
        return value.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+") ? value : null;
    }

    /** La copia firmada, con el mismo enlace, durante {@code copy-ttl} desde que se completó. */
    @Transactional
    public DocumentService.Content publicCopy(String token, Origin origin) {
        ContractSigner found = byToken(token, true);
        ContractSigning signing = lock(found.getSigning().getId());
        if (signing.getStatus() != SigningStatus.COMPLETADA || signing.getSignedDocument() == null) {
            throw new BadRequestException("Todavía faltan firmas: la copia firmada estará aquí cuando firmen todos");
        }
        event(signing, signerOf(signing, found.getId()), SigningEventType.COPIA_DESCARGADA, SIGNER_ACTOR, origin, null);
        return documents.open(signing.getSignedDocument());
    }

    // ==================================================================
    // Quién firma
    // ==================================================================

    /**
     * Los firmantes, en el mismo orden y con los mismos números que los huecos
     * del pie de firmas (ver ContractPdfService#signatures).
     */
    private List<ContractSigner> signersFor(RentalAgreement rental, InvoiceIssuer.Issuer issuer, Owner onBehalf) {
        List<ContractSigner> list = new ArrayList<>();
        List<Owner> owners = issuer.owners() == null ? List.of() : issuer.owners();
        if (owners.isEmpty()) {
            list.add(signer(list.size(), SignerRole.ARRENDADOR, 1, null, null, null,
                    issuer.name(), issuer.taxId(), issuer.email(), issuer.phone()));
        }
        for (int i = 0; i < owners.size(); i++) {
            Owner owner = owners.get(i);
            if (owner.isEntity() && onBehalf != null) {
                list.add(signer(list.size(), SignerRole.ARRENDADOR, i + 1, onBehalf.getId(), null, owner.getFullName(),
                        onBehalf.getFullName(), onBehalf.getDocumentId(), onBehalf.getEmail(), onBehalf.getPhone()));
            } else {
                list.add(signer(list.size(), SignerRole.ARRENDADOR, i + 1, owner.getId(), null, null,
                        owner.getFullName(), owner.getDocumentId(), owner.getEmail(), owner.getPhone()));
            }
        }
        List<Client> tenants = rental.tenants();
        for (int i = 0; i < tenants.size(); i++) {
            Client c = tenants.get(i);
            list.add(signer(list.size(), SignerRole.ARRENDATARIO, i + 1, null, c.getId(), null,
                    c.getFullName(), c.getDocumentId(), c.getEmail(), c.getPhone()));
        }
        List<Client> guarantors = rental.guarantors();
        for (int i = 0; i < guarantors.size(); i++) {
            Client c = guarantors.get(i);
            list.add(signer(list.size(), SignerRole.FIADOR, i + 1, null, c.getId(), null,
                    c.getFullName(), c.getDocumentId(), c.getEmail(), c.getPhone()));
        }
        return list;
    }

    private static ContractSigner signer(int position, SignerRole role, int number, Long ownerId, Long clientId,
                                         String onBehalfOf, String name, String taxId, String email, String phone) {
        return ContractSigner.builder()
                .position(position)
                .role(role)
                .roleNumber(number)
                .ownerId(ownerId)
                .clientId(clientId)
                .onBehalfOf(onBehalfOf)
                .fullName(trim(known(name) == null ? "(sin nombre en la ficha)" : name, 150))
                .taxId(trim(known(taxId), 20))
                .email(trim(known(email), 150))
                .phone(trim(known(phone), 30))
                .build();
    }

    /** La comunidad de bienes, si es la única que arrienda; si no, null. */
    private static Owner soleEntity(InvoiceIssuer.Issuer issuer) {
        List<Owner> owners = issuer.owners() == null ? List.of() : issuer.owners();
        return owners.size() == 1 && owners.get(0).isEntity() ? owners.get(0) : null;
    }

    private List<SigningDTOs.MemberOption> onBehalfOptions(Owner entity) {
        if (entity == null || entity.getId() == null) return List.of();
        return memberships.findByEntityId(entity.getId()).stream()
                .map(OwnerMembership::getMember)
                .map(member -> new SigningDTOs.MemberOption(member.getId(), member.getFullName(), member.getDocumentId()))
                .toList();
    }

    // ==================================================================
    // Firmar y sellar
    // ==================================================================

    /** Guarda la imagen de la firma y lo que se sabe de cómo se hizo. */
    private void keepSignature(ContractSigning signing, ContractSigner signer, byte[] signature,
                        SigningChannel channel, Origin origin) {
        String key = "firmas/" + signing.getId() + "/" + signer.getId() + "-" + UUID.randomUUID() + ".png";
        storage.put(key, "image/png", signature.length, new ByteArrayInputStream(signature));
        signer.setSignedAt(now());
        signer.setChannel(channel);
        signer.setSignedIp(trim(origin.ip(), 64));
        signer.setSignedUserAgent(trim(origin.userAgent(), 400));
        signer.setSignatureKey(key);
        signer.setSignatureSha256(sha256Hex(signature));
        signer.setDocumentSha256(signing.getOriginalSha256());
    }

    /**
     * Si ya han firmado todos, se sella. Si sellar falla —falta la contraseña
     * del certificado, por ejemplo—, las firmas no se pierden: se anota el
     * fallo y se puede reintentar desde la pestaña.
     */
    private void completeIfReady(ContractSigning signing, Origin origin) {
        if (!signing.isInProgress() || !signing.allSigned()) return;
        try {
            seal(signing, origin);
        } catch (RuntimeException | LinkageError e) {
            // LinkageError también: una clase que no está en la imagen nativa
            // (pasó con ImageIO, ver PngImages) no es una excepción sino un
            // Error, y sin esto deshacía la petición entera, firma incluida.
            log.warn("No se pudo sellar la firma {}: {}", signing.getId(), e.getMessage(), e);
            event(signing, null, SigningEventType.SELLO_FALLIDO, actorOrSigner(), origin, trim(e.getMessage(), 500));
        }
    }

    private void seal(ContractSigning signing, Origin origin) {
        byte[] original = read(documents.open(signing.getOriginalDocument()).stream());
        if (!sha256Hex(original).equals(signing.getOriginalSha256())) {
            throw new IllegalStateException("El PDF guardado ya no es el que se firmó: su huella no coincide");
        }
        List<SignatureSealService.SignerEvidence> evidence = signing.getSigners().stream()
                .map(signer -> new SignatureSealService.SignerEvidence(
                        com.storagemanager.storage_management.service.pdf.ContractPdfService.signerKey(
                                signer.getRole().name(), signer.getRoleNumber()),
                        roleLabel(signer, signing),
                        signer.getFullName(), signer.getTaxId(), signer.getOnBehalfOf(),
                        signer.getChannel() == SigningChannel.PRESENCIAL,
                        signer.getSignedAt(), signer.getSignedIp(), signer.getSignedUserAgent(),
                        Boolean.TRUE.equals(signer.getIdentityChecked()), signer.getIdentityCheckedBy(),
                        signer.getWitnessedBy(), signer.getDocumentSha256(), signer.getSignatureSha256(),
                        signer.getChannel() == SigningChannel.ENLACE && signer.getCodeVerifiedAt() != null
                                ? "código de un solo uso " + codeDelivery(signer) : null,
                        read(storage.open(signer.getSignatureKey()))))
                .toList();

        SignatureSealService.Sealed sealed = sealer.seal(new SignatureSealService.SealRequest(
                titleOf(signing), original, signing.getOriginalSha256(),
                SigningSlots.decode(signing.getSlots()), evidence));

        RentalAgreement rental = signing.getRentalAgreement();
        boolean exit = signing.getKind() == SigningKind.SALIDA;
        String fileName = (exit ? "contrato-salida-" : "contrato-") + Pdfs.slug(rental.getAgreementNumber())
                + "-firmado-" + LocalDate.now(MADRID) + ".pdf";
        Document signed = rentalDocuments.attach(rental.getId(), fileName, "application/pdf", sealed.content(),
                exit ? DocumentType.CONTRATO_SALIDA : DocumentType.CONTRATO_ALQUILER,
                (exit ? "Contrato de salida firmado" : "Contrato firmado") + " en BoxVault por todas las partes el "
                        + Pdfs.day(LocalDate.now(MADRID)) + (sealed.timestamped() ? ", con sello de tiempo" : ""));

        signing.setSignedDocument(signed);
        signing.setStatus(SigningStatus.COMPLETADA);
        signing.setCompletedAt(now());
        signings.save(signing);

        if (sealed.timestampProblem() != null) {
            event(signing, null, SigningEventType.SELLO_SIN_TSA, actorOrSigner(), origin,
                    trim(sealed.timestampProblem(), 500));
        }
        event(signing, null, SigningEventType.SELLADA, actorOrSigner(), origin,
                "Contrato firmado: " + signed.getFileName() + " · SHA-256 " + sha256Hex(sealed.content()));
        log.info("Firma {} completada: contrato {} firmado y sellado", signing.getId(), rental.getAgreementNumber());

        // El correo a todos sale cuando esto ya está guardado, y aparte: un
        // servidor de correo lento o caído no puede deshacer ni retrasar la firma.
        publisher.publishEvent(new SignedContract(signing.getId()));
    }

    /** Se acaba de completar y sellar una firma (lo recoge SignedContractMailer). */
    public record SignedContract(Long signingId) {}

    /**
     * Anota en el registro de una firma un correo mandado (o que no se pudo
     * mandar). Lo llama quien manda los correos, en su propia transacción.
     */
    @Transactional
    public void logMail(Long signingId, Long signerId, boolean sent, String detail) {
        ContractSigning signing = lock(signingId);
        ContractSigner signer = signerId == null ? null
                : signing.getSigners().stream().filter(s -> s.getId().equals(signerId)).findFirst().orElse(null);
        event(signing, signer, sent ? SigningEventType.CORREO_ENVIADO : SigningEventType.CORREO_FALLIDO,
                currentActor(), null, detail);
    }

    private String titleOf(ContractSigning signing) {
        RentalAgreement rental = signing.getRentalAgreement();
        String unit = rental.getStorageUnit() == null ? "" : " · " + rental.getStorageUnit().getName();
        return (signing.getKind() == SigningKind.SALIDA ? "Contrato de salida " : "Contrato de arrendamiento ")
                + rental.getAgreementNumber() + unit;
    }

    /** "Arrendataria 2", "Fiador"... sin género: "Parte arrendataria". */
    private static String roleLabel(ContractSigner signer, ContractSigning signing) {
        long sameRole = signing.getSigners().stream().filter(s -> s.getRole() == signer.getRole()).count();
        String label = switch (signer.getRole()) {
            case ARRENDADOR -> "PARTE ARRENDADORA";
            case ARRENDATARIO -> "PARTE ARRENDATARIA";
            case FIADOR -> "FIADOR";
        };
        return sameRole > 1 ? label + " " + signer.getRoleNumber() : label;
    }

    // ==================================================================
    // El registro encadenado
    // ==================================================================

    private void event(ContractSigning signing, ContractSigner signer, SigningEventType type, String actor,
                       Origin origin, String detail) {
        String previous = events.findFirstBySigningIdOrderByIdDesc(signing.getId())
                .map(SigningEvent::getHash)
                .orElse(null);
        SigningEvent event = SigningEvent.builder()
                .signingId(signing.getId())
                .signerId(signer == null ? null : signer.getId())
                .type(type)
                .at(now())
                .actor(trim(actor, 60))
                .ip(origin == null ? null : trim(origin.ip(), 64))
                .userAgent(origin == null ? null : trim(origin.userAgent(), 400))
                .detail(trim(detail, 500))
                .prevHash(previous)
                .build();
        event.setHash(hashOf(event));
        events.save(event);
    }

    /** La huella de una línea: todo lo que dice más la huella de la anterior. */
    static String hashOf(SigningEvent event) {
        String text = String.join("|",
                nz(event.getPrevHash()),
                String.valueOf(event.getSigningId()),
                event.getSignerId() == null ? "" : String.valueOf(event.getSignerId()),
                event.getType().name(),
                event.getAt().toString(),
                nz(event.getActor()),
                nz(event.getIp()),
                nz(event.getUserAgent()),
                nz(event.getDetail()));
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Si el registro sigue entero: cada línea cuelga de la anterior y su huella cuadra. */
    static boolean verifyChain(List<SigningEvent> chain) {
        String previous = null;
        for (SigningEvent event : chain) {
            if (!java.util.Objects.equals(previous, event.getPrevHash())) return false;
            if (!hashOf(event).equals(event.getHash())) return false;
            previous = event.getHash();
        }
        return true;
    }

    public boolean verifyEvents(Long signingId) {
        return verifyChain(events.findBySigningIdOrderByIdAsc(signingId));
    }

    // ==================================================================
    // Lo que se enseña
    // ==================================================================

    private SigningDTOs.SigningView view(ContractSigning signing) {
        List<SigningEvent> chain = events.findBySigningIdOrderByIdAsc(signing.getId());
        Map<Long, String> names = signing.getSigners().stream()
                .collect(Collectors.toMap(ContractSigner::getId, ContractSigner::getFullName, (a, b) -> a));
        Instant now = Instant.now();
        List<SigningDTOs.SignerView> signerViews = signing.getSigners().stream()
                .map(s -> new SigningDTOs.SignerView(s.getId(), s.getRole(), s.getRoleNumber(), s.getFullName(),
                        s.getTaxId(), s.getOnBehalfOf(), s.getEmail(), s.getPhone(),
                        s.hasSigned(), s.getSignedAt(), s.getChannel(),
                        Boolean.TRUE.equals(s.getIdentityChecked()), s.getIdentityCheckedBy(),
                        signing.isInProgress() && !s.hasSigned() && s.getTokenHash() != null
                                && s.getTokenExpiresAt() != null && s.getTokenExpiresAt().isAfter(now)
                                && s.getFailedAttempts() < MAX_ATTEMPTS,
                        s.getTokenExpiresAt(), s.getLinkOpenedAt() != null,
                        s.getFailedAttempts() >= MAX_ATTEMPTS,
                        !s.hasSigned() && s.getCodeHash() != null && s.getCodeExpiresAt() != null
                                && s.getCodeExpiresAt().isAfter(now),
                        s.getCodeExpiresAt()))
                .toList();
        List<SigningDTOs.EventView> eventViews = chain.stream()
                .map(e -> new SigningDTOs.EventView(e.getId(), e.getAt(), e.getType(), e.getActor(),
                        e.getSignerId() == null ? null : names.get(e.getSignerId()), e.getIp(), e.getDetail()))
                .toList();
        Document original = signing.getOriginalDocument();
        Document signed = signing.getSignedDocument();
        return new SigningDTOs.SigningView(signing.getId(), signing.getKind(), signing.getStatus(),
                original.getId(), original.getFileName(), signing.getOriginalSha256(),
                signed == null ? null : signed.getId(), signed == null ? null : signed.getFileName(),
                signing.allSigned(),
                signing.getCreatedAt() == null ? null : signing.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant(),
                signing.getCreatedBy(), signing.getCompletedAt(), signing.getCancelledAt(), signing.getCancelReason(),
                verifyChain(chain), signerViews, eventViews);
    }

    private SigningDTOs.PublicInfo publicInfoOf(ContractSigning signing, ContractSigner signer) {
        RentalAgreement rental = signing.getRentalAgreement();
        boolean completed = signing.getStatus() == SigningStatus.COMPLETADA;
        String preparedBy = users.findById(signing.getPreparedByUserId())
                .map(user -> user.getFullName() == null || user.getFullName().isBlank() ? user.getUsername() : user.getFullName())
                .orElse(null);
        String email = validEmail(signer.getEmail());
        boolean codeActive = signer.getCodeHash() != null && signer.getCodeExpiresAt() != null
                && signer.getCodeExpiresAt().isAfter(Instant.now());
        return new SigningDTOs.PublicInfo(titleOf(signing),
                rental.getStorageUnit() == null ? null : rental.getStorageUnit().getName(),
                rental.getAgreementNumber(), signer.getFullName(), roleLabel(signer, signing), signer.getOnBehalfOf(),
                signer.hasSigned(), completed, completed && signing.getSignedDocument() != null,
                signer.getTokenExpiresAt(), preparedBy,
                email != null && codeMailer.available() ? maskEmail(email) : null,
                codeActive);
    }

    // ==================================================================
    // Enlaces, tokens y permisos
    // ==================================================================

    /**
     * Quien firma con ese token. Todo lo que no vale —no existe, caducó, se
     * bloqueó, se canceló la firma— da el mismo 404, sin más pistas.
     *
     * @param afterwards si vale también para lo de después de firmar: ver en qué
     *                   quedó y descargar la copia firmada
     */
    private ContractSigner byToken(String token, boolean afterwards) {
        if (token == null || token.isBlank() || token.length() > 100) throw gone();
        ContractSigner signer = signers.findByTokenHash(sha256Hex(token.getBytes(StandardCharsets.UTF_8)))
                .orElseThrow(this::gone);
        ContractSigning signing = signer.getSigning();
        Instant now = Instant.now();
        if (signing.getStatus() == SigningStatus.CANCELADA) throw gone();
        if (signing.getStatus() == SigningStatus.COMPLETADA) {
            boolean copyWindow = afterwards && signing.getCompletedAt() != null
                    && signing.getCompletedAt().plus(properties.getCopyTtl()).isAfter(now);
            if (!copyWindow) throw gone();
            return signer;
        }
        if (signer.getFailedAttempts() >= MAX_ATTEMPTS) throw gone();
        if (signer.hasSigned()) {
            // Ya firmó: el enlace sirve para ver que falta el resto y, luego, para la copia.
            if (!afterwards) throw gone();
            return signer;
        }
        if (signer.getTokenExpiresAt() == null || !signer.getTokenExpiresAt().isAfter(now)) throw gone();
        return signer;
    }

    /**
     * Lo que se archiva al firmar con un enlace queda a nombre de quien preparó
     * la firma, como si lo hiciera él, y con su ámbito: igual que la subida
     * desde el móvil. Si ya no está de alta o no puede tocar alquileres, el
     * enlace deja de valer.
     */
    private void asPreparer(ContractSigning signing, Runnable work) {
        AppUser user = users.findById(signing.getPreparedByUserId())
                .filter(AppUser::isActive)
                .orElseThrow(this::gone);
        CurrentUser preparer = CurrentUser.of(user);
        if (!preparer.can(AccessArea.ALQUILERES, AccessLevel.ESCRIBIR)) throw gone();

        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(preparer, null, List.of()));
        SecurityContextHolder.setContext(context);
        try {
            work.run();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private ContractSigning lockOwned(Long rentalId, Long signingId) {
        rentals.getAgreementById(rentalId);
        ContractSigning signing = lock(signingId);
        if (!signing.getRentalAgreement().getId().equals(rentalId)) {
            throw new ResourceNotFoundException("No hay ninguna firma " + signingId + " en este contrato");
        }
        return signing;
    }

    private ContractSigning lock(Long signingId) {
        return signings.lockById(signingId)
                .orElseThrow(() -> new ResourceNotFoundException("No hay ninguna firma " + signingId));
    }

    private static ContractSigner signerOf(ContractSigning signing, Long signerId) {
        return signing.getSigners().stream()
                .filter(signer -> signer.getId().equals(signerId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Esa persona no firma este contrato"));
    }

    private static void requireInProgress(ContractSigning signing) {
        if (!signing.isInProgress()) {
            throw new BadRequestException(signing.getStatus() == SigningStatus.COMPLETADA
                    ? "Esta firma ya está completada" : "Esta firma está cancelada");
        }
    }

    /** La base de los enlaces: la dirección pública configurada o, si no hay, desde la que se pidió. */
    private String baseUrl(String origin) {
        String configured = properties.getPublicUrl();
        if (configured != null && !configured.isBlank()) return stripSlash(configured.trim());
        if (origin != null && origin.trim().matches("https?://[^/\\s?#]+")) return stripSlash(origin.trim());
        throw new BadRequestException("No hay una dirección pública para los enlaces (BOXVAULT_PUBLIC_URL)");
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * El enlace de WhatsApp con el mensaje ya escrito, al móvil del firmante. Un
     * móvil español de nueve cifras lleva el prefijo de España delante.
     */
    private String whatsappUrl(ContractSigner signer, ContractSigning signing, String url) {
        String firstName = signer.getFullName().split("\\s+")[0];
        String text = "Hola " + firstName + ", aquí tienes el " + shortTitle(signing)
                + " para leerlo y firmarlo desde el móvil: " + url
                + "\nEl enlace es personal y caduca el " + DAY_TIME.format(signer.getTokenExpiresAt()) + "."
                + "\nPara abrirlo te pedirá un código de seis cifras que te mandaremos aparte.";
        return whatsappTo(signer.getPhone(), text);
    }

    /** wa.me con el mensaje escrito, o null si el teléfono no vale para WhatsApp. */
    private static String whatsappTo(String phone, String text) {
        String digits = phone == null ? "" : phone.replaceAll("\\D", "");
        if (digits.startsWith("00")) digits = digits.substring(2);
        if (digits.length() == 9 && "6789".indexOf(digits.charAt(0)) >= 0) digits = "34" + digits;
        if (digits.length() < 10) return null;
        return "https://wa.me/" + digits + "?text=" + URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** "contrato CON-2026-03 · trastero 3", para un mensaje. */
    private String shortTitle(ContractSigning signing) {
        return titleOf(signing).toLowerCase(Pdfs.ES).replace("contrato de arrendamiento", "contrato");
    }

    /** Una firma es un PNG pequeño; cualquier otra cosa no se guarda. */
    private static void requirePng(byte[] signature) {
        if (signature == null || signature.length < 100) {
            throw new BadRequestException("Falta la firma");
        }
        if (signature.length > MAX_SIGNATURE_BYTES) {
            throw new BadRequestException("La imagen de la firma es demasiado grande");
        }
        if ((signature[0] & 0xFF) != 0x89 || signature[1] != 'P' || signature[2] != 'N' || signature[3] != 'G') {
            throw new BadRequestException("La firma tiene que llegar como imagen PNG");
        }
    }

    private ResourceNotFoundException gone() {
        return new ResourceNotFoundException("Este enlace para firmar ya no vale: ha caducado, se ha usado o se ha "
                + "anulado. Pide uno nuevo a quien te lo envió.");
    }

    private static String currentActor() {
        CurrentUser user = Authenticated.userOrNull();
        return user == null ? "sistema" : user.username();
    }

    /** En el sellado puede estar un usuario (firma en persona) o un firmante con su enlace. */
    private static String actorOrSigner() {
        CurrentUser user = Authenticated.userOrNull();
        return user == null ? SIGNER_ACTOR : user.username();
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** La hora de las evidencias, a milisegundos: así se guarda y se relee igual, y la huella cuadra. */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] read(InputStream stream) {
        try (InputStream in = stream) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer un fichero de la firma", e);
        }
    }

    private static String known(String value) {
        return value == null || value.isBlank() || value.equals(InvoicingProperties.MISSING) ? null : value.trim();
    }

    private static String trim(String value, int max) {
        if (value == null) return null;
        String text = value.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
