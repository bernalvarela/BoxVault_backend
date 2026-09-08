package com.storagemanager.storage_management.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Quien entra en la aplicación. Se llama AppUser y no User porque "user" es
 * palabra reservada en varias bases de datos; la tabla es {@code app_users}.
 * <p>
 * Lo que puede hacer sale de dos sitios: el nivel por área, que viene de su
 * perfil y puede llevar ajustes propios ({@link UserPermission}), y hasta dónde
 * llega, que es {@link #fullScope} o, si no, las unidades concedidas
 * ({@link UserUnitScope}) con todo lo que cuelga de ellas.
 */
@Entity
@Table(name = "app_users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Con lo que se entra. Se compara en minúsculas: se guarda ya normalizado. */
    @Column(nullable = false, unique = true, length = 60)
    private String username;

    @Column(nullable = false, length = 150)
    private String fullName;

    @Column(length = 150)
    private String email;

    /** BCrypt, con el prefijo del algoritmo que pone DelegatingPasswordEncoder. */
    @Column(nullable = false, length = 100)
    private String passwordHash;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    /**
     * Ve todas las unidades, sin mirar las concesiones. Es lo que distingue a un
     * gestor (los impuestos de todas las unidades) de quien sólo lleva un local.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean fullScope = false;

    /** Un usuario dado de baja no entra, pero sus rastros de auditoría siguen valiendo. */
    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    /** La contraseña la puso un administrador: hay que cambiarla al entrar. */
    @Column(nullable = false)
    @Builder.Default
    private boolean mustChangePassword = false;

    /**
     * Desde cuándo valen sus tokens. Cambiar la contraseña o dar de baja al
     * usuario mueve esta marca y todo lo emitido antes deja de servir, que es la
     * forma de echar a alguien sin guardar los tokens en ninguna parte.
     */
    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime tokensValidFrom = LocalDateTime.now();

    private LocalDateTime lastLoginAt;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Builder.Default
    private List<UserPermission> permissions = new ArrayList<>();

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
