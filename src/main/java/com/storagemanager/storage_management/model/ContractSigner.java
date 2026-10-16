package com.storagemanager.storage_management.model;

import com.storagemanager.storage_management.model.enums.SignerRole;
import com.storagemanager.storage_management.model.enums.SigningChannel;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Una persona que tiene que firmar un contrato, y lo que quedó de su firma.
 * <p>
 * El nombre y el NIF se copian al preparar la firma y no se leen después de la
 * ficha: si mañana se corrige la ficha, lo que dice esta firma es lo que había
 * cuando se firmó. Por eso el cliente o el propietario van por id y no como
 * relación.
 * <p>
 * El enlace para firmar desde el móvil no se guarda: se guarda su huella
 * ({@link #tokenHash}). Con la base de datos en la mano no se puede firmar por
 * nadie.
 */
@Entity
@Table(name = "contract_signers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContractSigner {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "signing_id", nullable = false)
    private ContractSigning signing;

    /** Orden en el pie de firmas y en la lista. */
    @Column(nullable = false)
    private Integer position;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private SignerRole role;

    /** Su número dentro de su papel, desde 1: el segundo arrendatario es el 2. */
    @Column(name = "role_number", nullable = false)
    private Integer roleNumber;

    private Long ownerId;

    private Long clientId;

    /** Si firma por una comunidad de bienes: su nombre. */
    @Column(length = 150)
    private String onBehalfOf;

    @Column(nullable = false, length = 150)
    private String fullName;

    @Column(length = 20)
    private String taxId;

    @Column(length = 150)
    private String email;

    @Column(length = 30)
    private String phone;

    // --- El enlace -----------------------------------------------------------

    @Column(length = 64)
    private String tokenHash;

    private Instant tokenCreatedAt;

    private Instant tokenExpiresAt;

    /** Códigos tecleados mal con el enlace; al quinto, el enlace deja de valer. */
    @Column(nullable = false)
    @Builder.Default
    private Integer failedAttempts = 0;

    private Instant linkOpenedAt;

    // --- El código por correo ---------------------------------------------
    //
    // El enlace solo no basta: se puede reenviar o abrir en otro móvil. Antes de
    // ver el contrato y para firmarlo hay que teclear un código que se manda al
    // correo de su ficha, y eso prueba que quien firma lee ese correo. Del
    // código, como del enlace, sólo se guarda la huella.

    @Column(length = 64)
    private String codeHash;

    private Instant codeSentAt;

    private Instant codeExpiresAt;

    /** Cuántos códigos se han pedido con este enlace. */
    @Column(nullable = false)
    @Builder.Default
    private Integer codeSends = 0;

    /** A qué correo mandó BoxVault el código; null si lo entregó alguien a mano. */
    @Column(length = 150)
    private String codeEmail;

    /** Quién generó el código para hacérselo llegar por otro medio (WhatsApp, SMS...). */
    @Column(length = 60)
    private String codeDeliveredBy;

    /** Cuándo tecleó bien el código con el que firmó. */
    private Instant codeVerifiedAt;

    // --- La firma ------------------------------------------------------------

    private Instant signedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 12)
    private SigningChannel channel;

    @Column(length = 64)
    private String signedIp;

    @Column(length = 400)
    private String signedUserAgent;

    /** Dónde está la imagen de la firma en el almacén de ficheros. */
    @Column(length = 255)
    private String signatureKey;

    /** SHA-256 de esa imagen. */
    @Column(length = 64)
    private String signatureSha256;

    /** SHA-256 del PDF que tenía delante al firmar. */
    @Column(length = 64)
    private String documentSha256;

    /** Si quien gestiona comprobó su identidad con el DNI delante. */
    @Column(nullable = false)
    @Builder.Default
    private Boolean identityChecked = false;

    @Column(length = 60)
    private String identityCheckedBy;

    /** Quién estaba con la tableta cuando firmó en persona. */
    @Column(length = 60)
    private String witnessedBy;

    public boolean hasSigned() {
        return signedAt != null;
    }
}
