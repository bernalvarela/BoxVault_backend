package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.model.enums.SignerRole;
import com.storagemanager.storage_management.model.enums.SigningChannel;
import com.storagemanager.storage_management.model.enums.SigningEventType;
import com.storagemanager.storage_management.model.enums.SigningKind;
import com.storagemanager.storage_management.model.enums.SigningStatus;

import java.time.Instant;
import java.util.List;

/** Lo que va y viene de la firma de contratos (ver ContractSigningService). */
public final class SigningDTOs {

    private SigningDTOs() {}

    /** La pestaña "Firma" de un contrato: sus firmas y lo que hace falta para preparar otra. */
    public record Overview(List<SigningView> signings,
                           /** Si la única arrendadora es una comunidad de bienes: los comuneros que pueden firmar por ella. */
                           List<MemberOption> onBehalfOptions,
                           String onBehalfOf,
                           /** Si ya se puede firmar el contrato de salida (el contrato está finalizado o con baja). */
                           boolean exitAllowed) {}

    public record MemberOption(Long ownerId, String fullName, String taxId) {}

    public record SigningView(Long id, SigningKind kind, SigningStatus status,
                              Long originalDocumentId, String originalFileName, String originalSha256,
                              Long signedDocumentId, String signedFileName,
                              boolean allSigned, Instant createdAt, String createdBy,
                              Instant completedAt, Instant cancelledAt, String cancelReason,
                              /** Si el registro de eventos sigue encadenado de principio a fin. */
                              boolean chainIntact,
                              List<SignerView> signers, List<EventView> events) {}

    public record SignerView(Long id, SignerRole role, int roleNumber, String fullName, String taxId,
                             String onBehalfOf, String email, String phone,
                             boolean signed, Instant signedAt, SigningChannel channel,
                             boolean identityChecked, String identityCheckedBy,
                             /** Si tiene un enlace que todavía vale. */
                             boolean linkActive, Instant linkExpiresAt, boolean linkOpened,
                             boolean linkBlocked) {}

    public record EventView(Long id, Instant at, SigningEventType type, String actor, String signerName,
                            String ip, String detail) {}

    /**
     * Preparar una firma.
     *
     * @param onBehalfOwnerId el comunero que firma por la comunidad de bienes, cuando hace falta
     */
    public record PrepareRequest(SigningKind kind, Long onBehalfOwnerId) {}

    public record CancelRequest(String reason) {}

    /**
     * Pedir el enlace de un firmante.
     *
     * @param origin la dirección desde la que se usa BoxVault, por si no hay una
     *               pública configurada
     */
    public record LinkRequest(String origin) {}

    /**
     * El enlace para firmar desde el móvil.
     *
     * @param whatsappUrl el mismo enlace, listo para mandarlo por WhatsApp con su
     *                    mensaje; null si el firmante no tiene un móvil que valga
     */
    public record LinkView(String url, Instant expiresAt, String whatsappUrl) {}

    // --- Para quien firma con el enlace, sin sesión ---------------------------

    public record PublicInfo(String title, String unitName, String agreementNumber,
                             String signerName, String role, String onBehalfOf,
                             boolean signed, boolean completed, boolean copyAvailable,
                             Instant expiresAt, String preparedBy) {}
}
