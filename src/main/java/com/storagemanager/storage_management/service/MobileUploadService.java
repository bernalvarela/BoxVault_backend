package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.dto.ClientDocumentDTO;
import com.storagemanager.storage_management.dto.MobileUploadDTOs;
import com.storagemanager.storage_management.dto.MobileUploadDTOs.MobileUploadStatus;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.AppUser;
import com.storagemanager.storage_management.model.Client;
import com.storagemanager.storage_management.model.enums.AccessArea;
import com.storagemanager.storage_management.model.enums.AccessLevel;
import com.storagemanager.storage_management.model.enums.DocumentType;
import com.storagemanager.storage_management.repository.AppUserRepository;
import com.storagemanager.storage_management.repository.ClientRepository;
import com.storagemanager.storage_management.security.Authenticated;
import com.storagemanager.storage_management.security.CurrentUser;
import com.storagemanager.storage_management.security.UnitScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Subir un documento de un cliente con el móvil mientras se trabaja en el
 * ordenador: el ordenador abre una sesión, enseña un QR con su token, y el móvil
 * hace la foto y la sube con ese token, sin iniciar sesión.
 * <p>
 * El token es la llave entera, así que se cuida en consecuencia:
 * <ul>
 *   <li>32 bytes aleatorios: no se puede adivinar;</li>
 *   <li>vale para un único documento, de un cliente y un tipo que se fijaron en
 *       el ordenador; el móvil no elige nada;</li>
 *   <li>caduca a los {@link #TTL} minutos, y se puede cancelar antes;</li>
 *   <li>lo subido queda a nombre de quien abrió la sesión, con sus permisos y su
 *       ámbito de ese momento: si entretanto le han quitado el permiso, le han
 *       dado de baja o ha cambiado la contraseña, el token deja de valer.</li>
 * </ul>
 * Las sesiones viven en memoria y no en la base de datos: duran minutos, y si el
 * servidor se reinicia basta con volver a pedir el QR.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MobileUploadService {

    static final Duration TTL = Duration.ofMinutes(10);

    /** Un tope para que nadie llene la memoria abriendo sesiones sin parar. */
    private static final int MAX_SESSIONS = 200;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ClientRepository clientRepository;
    private final AppUserRepository userRepository;
    private final ClientDocumentService clientDocumentService;
    private final UnitScope unitScope;

    /** Por token, que es como llega el móvil. */
    private final Map<String, Session> byToken = new ConcurrentHashMap<>();

    private static final class Session {
        final String id = UUID.randomUUID().toString();
        final String token;
        final Long clientId;
        final DocumentType documentType;
        final String description;
        final Long userId;
        final Instant createdAt = Instant.now();
        final Instant expiresAt = createdAt.plus(TTL);
        volatile MobileUploadStatus status = MobileUploadStatus.PENDING;
        volatile boolean opened;
        /** Hay una subida en curso con este token; ver {@link MobileUploadService#upload}. */
        volatile boolean uploading;
        volatile Long documentId;

        Session(String token, Long clientId, DocumentType documentType, String description, Long userId) {
            this.token = token;
            this.clientId = clientId;
            this.documentType = documentType;
            this.description = description;
            this.userId = userId;
        }

        boolean expired() {
            return status == MobileUploadStatus.PENDING && Instant.now().isAfter(expiresAt);
        }

        MobileUploadStatus currentStatus() {
            return expired() ? MobileUploadStatus.EXPIRED : status;
        }
    }

    // ------------------------------------------------------------------
    // Desde el ordenador, con sesión
    // ------------------------------------------------------------------

    public MobileUploadDTOs.Created create(Long clientId, MobileUploadDTOs.CreateRequest request) {
        requireClient(clientId);
        DocumentType type = request.documentType() != null ? request.documentType() : DocumentType.OTRO;
        if (!DocumentType.forClient().contains(type)) {
            throw new BadRequestException("Un documento de tipo " + type + " no va en la ficha del cliente");
        }

        purge();
        if (byToken.size() >= MAX_SESSIONS) {
            throw new BadRequestException("Hay demasiadas subidas desde el móvil abiertas; espera unos minutos");
        }

        Session session = new Session(newToken(), clientId, type, request.description(), Authenticated.user().id());
        byToken.put(session.token, session);
        return new MobileUploadDTOs.Created(session.id, session.token, session.expiresAt);
    }

    public MobileUploadDTOs.Status status(Long clientId, String id) {
        Session session = mine(clientId, id);
        return new MobileUploadDTOs.Status(session.currentStatus(), session.opened, session.expiresAt, session.documentId);
    }

    public void cancel(Long clientId, String id) {
        Session session = mine(clientId, id);
        synchronized (session) {
            if (session.status == MobileUploadStatus.PENDING) session.status = MobileUploadStatus.CANCELLED;
        }
    }

    // ------------------------------------------------------------------
    // Desde el móvil, sólo con el token
    // ------------------------------------------------------------------

    public MobileUploadDTOs.Info info(String token) {
        Session session = pending(token);
        session.opened = true;
        Client client = clientRepository.findById(session.clientId).orElseThrow(this::gone);
        return new MobileUploadDTOs.Info(client.getFullName(), session.documentType, session.description, session.expiresAt);
    }

    public ClientDocumentDTO upload(String token, MultipartFile file) {
        Session session = pending(token);
        CurrentUser owner = ownerOf(session);

        // Se reserva antes de subir y no después: dos envíos casi a la vez (un
        // doble toque en el móvil) no pueden archivar dos copias con el mismo token.
        synchronized (session) {
            if (session.uploading || session.currentStatus() != MobileUploadStatus.PENDING) throw gone();
            session.uploading = true;
        }

        // La subida va por el mismo servicio que la del ordenador, como si la
        // hiciera quien abrió la sesión: comprueba su ámbito y queda a su nombre
        // en la auditoría.
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(owner, null, List.of()));
        SecurityContextHolder.setContext(context);
        try {
            ClientDocumentDTO saved = clientDocumentService.upload(
                    session.clientId, file, session.documentType, session.description);
            // El ordenador ve DONE cuando el documento ya está guardado, no antes:
            // así, al recargar la lista, lo encuentra.
            session.documentId = saved.getId();
            session.status = MobileUploadStatus.DONE;
            return saved;
        } finally {
            // Si falló, no se archivó nada y el token sigue valiendo para reintentar.
            session.uploading = false;
            SecurityContextHolder.setContext(previous);
        }
    }

    // ------------------------------------------------------------------

    private Session pending(String token) {
        Session session = token == null ? null : byToken.get(token);
        if (session == null || session.currentStatus() != MobileUploadStatus.PENDING) throw gone();
        return session;
    }

    /** Una sesión de este cliente abierta por el usuario que pregunta; las de otros no se ven. */
    private Session mine(Long clientId, String id) {
        Long userId = Authenticated.user().id();
        return byToken.values().stream()
                .filter(s -> s.id.equals(id) && s.clientId.equals(clientId) && s.userId.equals(userId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("No hay ninguna subida desde el móvil " + id));
    }

    /**
     * Quien abrió la sesión, tal como está ahora. Es la misma comprobación que
     * hace el inicio de sesión con cada petición (ver CookieJwtAuthentication):
     * sigue de alta, no ha invalidado sus sesiones después de abrir ésta, y
     * todavía puede subir documentos de clientes.
     */
    private CurrentUser ownerOf(Session session) {
        AppUser user = userRepository.findById(session.userId)
                .filter(AppUser::isActive)
                .orElseThrow(this::gone);
        Instant validFrom = user.getTokensValidFrom()
                .atZone(ZoneId.systemDefault()).toInstant().truncatedTo(ChronoUnit.SECONDS);
        if (session.createdAt.isBefore(validFrom)) throw gone();

        CurrentUser current = CurrentUser.of(user);
        if (!current.can(AccessArea.CLIENTES, AccessLevel.ESCRIBIR)) {
            throw new AccessDeniedException("Quien abrió esta subida ya no puede archivar documentos de clientes");
        }
        return current;
    }

    private void requireClient(Long clientId) {
        if (!clientRepository.existsById(clientId)) {
            throw new ResourceNotFoundException("Client not found with id: " + clientId);
        }
        unitScope.requireClientVisible(clientId);
    }

    /**
     * Las caducadas y las ya cerradas se quedan un rato más, lo justo para que
     * el ordenador vea en qué acabaron, y después se olvidan.
     */
    private void purge() {
        Instant limit = Instant.now().minus(TTL);
        byToken.values().removeIf(s -> s.expiresAt.isBefore(limit));
    }

    /** Lo mismo para un token que no existe, que caducó o que ya se usó: no se da más pista. */
    private ResourceNotFoundException gone() {
        return new ResourceNotFoundException("El enlace ha caducado o ya se ha usado. Pide un QR nuevo en el ordenador.");
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
