package com.storagemanager.storage_management.security;

import com.storagemanager.storage_management.model.AppUser;
import com.storagemanager.storage_management.model.enums.AccessArea;
import com.storagemanager.storage_management.model.enums.AccessLevel;

import java.util.EnumMap;
import java.util.Map;

/**
 * Quién está pidiendo, ya resuelto: sus permisos por área con los ajustes
 * propios aplicados sobre los del perfil, y hasta dónde llega. Se calcula en
 * cada petición a partir de la base de datos, así que quitarle permisos a
 * alguien surte efecto de inmediato.
 */
public record CurrentUser(
        Long id,
        String username,
        String fullName,
        String roleName,
        boolean fullScope,
        boolean mustChangePassword,
        Map<AccessArea, AccessLevel> permissions) {

    /**
     * Mezcla el perfil con los ajustes del usuario. El ajuste manda en los dos
     * sentidos: puede dar de más y puede quitar.
     */
    public static CurrentUser of(AppUser user) {
        Map<AccessArea, AccessLevel> levels = new EnumMap<>(AccessArea.class);
        for (AccessArea area : AccessArea.values()) {
            levels.put(area, AccessLevel.NINGUNO);
        }
        user.getRole().getPermissions().forEach(p -> levels.put(p.getArea(), p.getLevel()));
        user.getPermissions().forEach(p -> levels.put(p.getArea(), p.getLevel()));

        return new CurrentUser(
                user.getId(),
                user.getUsername(),
                user.getFullName(),
                user.getRole().getName(),
                user.isFullScope(),
                user.isMustChangePassword(),
                Map.copyOf(levels));
    }

    public AccessLevel levelOn(AccessArea area) {
        return permissions.getOrDefault(area, AccessLevel.NINGUNO);
    }

    public boolean can(AccessArea area, AccessLevel required) {
        return levelOn(area).allows(required);
    }

    /** Quien administra usuarios administra la aplicación. */
    public boolean isAdmin() {
        return can(AccessArea.USUARIOS, AccessLevel.ADMINISTRAR);
    }
}
