package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.model.AppUser;
import com.storagemanager.storage_management.model.Role;
import com.storagemanager.storage_management.model.enums.AccessArea;
import com.storagemanager.storage_management.model.enums.AccessLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Lo que entra y sale de la pantalla de usuarios. */
public final class UserDTOs {

    private UserDTOs() {}

    /**
     * Un usuario tal como lo ve un administrador. Los permisos que lleva son los
     * ajustes propios, no los del perfil: el perfil se ve aparte, para poder
     * distinguir lo heredado de lo tocado a mano.
     */
    public record UserDTO(
            Long id,
            String username,
            String fullName,
            String email,
            Long roleId,
            String roleName,
            boolean fullScope,
            boolean active,
            boolean mustChangePassword,
            Map<AccessArea, AccessLevel> overrides,
            List<Long> unitScopeIds,
            /** Edificios concedidos enteros: sus unidades de hoy y las de mañana. */
            List<Long> buildingScopeIds,
            LocalDateTime lastLoginAt) {

        public static UserDTO of(AppUser user, List<Long> unitScopeIds, List<Long> buildingScopeIds) {
            return new UserDTO(
                    user.getId(),
                    user.getUsername(),
                    user.getFullName(),
                    user.getEmail(),
                    user.getRole().getId(),
                    user.getRole().getName(),
                    user.isFullScope(),
                    user.isActive(),
                    user.isMustChangePassword(),
                    user.getPermissions().stream()
                            .collect(Collectors.toMap(p -> p.getArea(), p -> p.getLevel())),
                    unitScopeIds,
                    buildingScopeIds,
                    user.getLastLoginAt());
        }
    }

    /**
     * Alta y modificación. {@code password} sólo se mira al crear o cuando un
     * administrador la reinicia; en una modificación normal va vacío y la
     * contraseña se queda como estaba.
     */
    public record UserRequest(
            @NotBlank String username,
            @NotBlank String fullName,
            String email,
            String password,
            @NotNull Long roleId,
            boolean fullScope,
            boolean active,
            Map<AccessArea, AccessLevel> overrides,
            List<Long> unitScopeIds,
            List<Long> buildingScopeIds) {}

    /** Un perfil con sus niveles por área, para el desplegable y la rejilla. */
    public record RoleDTO(Long id, String name, String description, boolean systemRole,
                          Map<AccessArea, AccessLevel> permissions) {

        public static RoleDTO of(Role role) {
            return new RoleDTO(
                    role.getId(),
                    role.getName(),
                    role.getDescription(),
                    role.isSystemRole(),
                    role.getPermissions().stream()
                            .collect(Collectors.toMap(p -> p.getArea(), p -> p.getLevel())));
        }
    }
}
