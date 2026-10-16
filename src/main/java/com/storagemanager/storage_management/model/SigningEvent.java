package com.storagemanager.storage_management.model;

import com.storagemanager.storage_management.model.enums.SigningEventType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Una línea del registro de una firma: qué pasó, cuándo, quién y desde dónde.
 * <p>
 * Las líneas van encadenadas: cada una guarda la huella de la anterior
 * ({@link #prevHash}) y la suya propia, calculada sobre todo lo que dice y esa
 * huella anterior. Borrar o cambiar una línea del medio rompe la cadena desde
 * ahí, y eso se ve al comprobarla (ContractSigningService#verifyEvents). No
 * impide que alguien con acceso a la base de datos la rehaga entera, pero sí
 * que se retoque una línea sin que se note.
 * <p>
 * La firma y el firmante van por id: el registro tiene que sobrevivir a todo.
 */
@Entity
@Table(name = "signing_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SigningEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "signing_id", nullable = false)
    private Long signingId;

    @Column(name = "signer_id")
    private Long signerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SigningEventType type;

    @Column(name = "happened_at", nullable = false)
    private Instant at;

    /** Quién lo hizo: el usuario de BoxVault, o "firmante" si fue con un enlace. */
    @Column(length = 60)
    private String actor;

    @Column(length = 64)
    private String ip;

    @Column(length = 400)
    private String userAgent;

    @Column(length = 500)
    private String detail;

    @Column(name = "prev_hash", length = 64)
    private String prevHash;

    @Column(nullable = false, length = 64)
    private String hash;
}
