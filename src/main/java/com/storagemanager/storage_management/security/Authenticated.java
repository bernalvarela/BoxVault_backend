package com.storagemanager.storage_management.security;

import com.storagemanager.storage_management.model.enums.AccessArea;
import com.storagemanager.storage_management.model.enums.AccessLevel;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Quién está pidiendo y qué puede hacer, para los sitios donde no basta con la
 * anotación: consultas que hay que filtrar, o comprobaciones a mitad de un
 * método.
 * <p>
 * Es además el bean {@code access} que usan las anotaciones de los
 * controladores: {@code @PreAuthorize("@access.can('CLIENTES','ESCRIBIR')")}.
 */
@Component("access")
public class Authenticated {

    /** El usuario de la petición en curso, o null si no hay ninguno (login). */
    public static CurrentUser userOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser user) {
            return user;
        }
        return null;
    }

    /** El usuario de la petición en curso; falla si la petición no venía autenticada. */
    public static CurrentUser user() {
        CurrentUser user = userOrNull();
        if (user == null) {
            throw new AccessDeniedException("No hay ningún usuario en la petición");
        }
        return user;
    }

    /** Lo que llaman las anotaciones de los controladores. */
    public boolean can(String area, String level) {
        CurrentUser user = userOrNull();
        return user != null && user.can(AccessArea.valueOf(area), AccessLevel.valueOf(level));
    }

    /** Comprobación desde código, con el mismo 403 que daría la anotación. */
    public static void require(AccessArea area, AccessLevel level) {
        if (!user().can(area, level)) {
            throw new AccessDeniedException("Sin permiso de " + level + " en " + area);
        }
    }
}
