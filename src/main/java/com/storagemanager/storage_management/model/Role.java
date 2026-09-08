package com.storagemanager.storage_management.model;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Un perfil: un juego de permisos con nombre, para no repetirlo usuario por
 * usuario ("gestor", "consulta", "administrador"). Lo que trae el perfil es el
 * punto de partida; cada usuario puede llevar ajustes propios encima
 * ({@link UserPermission}).
 */
@Entity
@Table(name = "roles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 60)
    private String name;

    @Column(length = 255)
    private String description;

    /**
     * Los perfiles que crea la propia aplicación al arrancar. No se pueden
     * borrar: siempre tiene que quedar al menos por dónde entrar.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean systemRole = false;

    @OneToMany(mappedBy = "role", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Builder.Default
    private List<RolePermission> permissions = new ArrayList<>();
}
