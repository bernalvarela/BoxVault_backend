package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.model.enums.AccessArea;
import com.storagemanager.storage_management.model.enums.AccessLevel;
import com.storagemanager.storage_management.security.CurrentUser;
import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/** Lo que entra y sale de {@code /api/auth}. */
public final class AuthDTOs {

    private AuthDTOs() {}

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {}

    /**
     * Quién soy y qué puedo hacer. El frontend monta el menú con esto: un área en
     * NINGUNO no pinta su entrada (ver Navbar.jsx).
     */
    public record MeResponse(
            Long id,
            String username,
            String fullName,
            String roleName,
            boolean fullScope,
            boolean mustChangePassword,
            Map<AccessArea, AccessLevel> permissions) {

        public static MeResponse of(CurrentUser user) {
            return new MeResponse(
                    user.id(),
                    user.username(),
                    user.fullName(),
                    user.roleName(),
                    user.fullScope(),
                    user.mustChangePassword(),
                    user.permissions());
        }
    }
}
