package com.storagemanager.storage_management.model;

import com.storagemanager.storage_management.model.enums.AccessArea;
import com.storagemanager.storage_management.model.enums.AccessLevel;
import jakarta.persistence.*;
import lombok.*;

/**
 * Un ajuste sobre lo que da el perfil, para un área. Manda sobre el perfil, en
 * los dos sentidos: sirve tanto para dar de más (impuestos en LEER a alguien
 * cuyo perfil no los tiene) como para quitar (clientes en LEER a un gestor que
 * por el perfil podría escribirlos).
 */
@Entity
@Table(name = "user_permissions",
        uniqueConstraints = @UniqueConstraint(name = "uk_user_permissions_area", columnNames = {"user_id", "area"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserPermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AccessArea area;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccessLevel level;
}
