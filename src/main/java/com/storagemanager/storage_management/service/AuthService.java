package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.model.AppUser;
import com.storagemanager.storage_management.repository.AppUserRepository;
import com.storagemanager.storage_management.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * Entrar, renovar la sesión y cambiar la contraseña.
 * <p>
 * Un usuario de baja o una contraseña que no es dan el mismo error a propósito:
 * desde fuera no se puede distinguir un nombre que existe de uno que no.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public AppUser login(String username, String password) {
        AppUser user = users.findByUsername(normalize(username)).orElse(null);
        if (user == null || !user.isActive() || !passwordEncoder.matches(password, user.getPasswordHash())) {
            // El tiempo que tarda en fallar no debería decir si el usuario existe;
            // por eso se comprueba la contraseña aunque el usuario no esté.
            if (user == null) passwordEncoder.matches(password, "{bcrypt}$2a$10$invalidinvalidinvalidinvalidinvalidinvalidinvalidinva");
            log.info("Intento de acceso fallido para '{}'", username);
            throw new BadCredentialsException("Usuario o contraseña incorrectos");
        }
        user.setLastLoginAt(LocalDateTime.now());
        return user;
    }

    /** El usuario del token de refresco, comprobando que siga valiendo. */
    public AppUser activeUser(Long userId) {
        AppUser user = users.findById(userId).orElse(null);
        if (user == null || !user.isActive()) {
            throw new BadCredentialsException("La sesión ya no vale");
        }
        return user;
    }

    /**
     * Cambia la contraseña del que la pide. Mueve {@code tokensValidFrom}, así
     * que las demás sesiones abiertas de ese usuario dejan de valer.
     */
    @Transactional
    public void changeOwnPassword(CurrentUser current, String currentPassword, String newPassword) {
        AppUser user = users.findById(current.id())
                .orElseThrow(() -> new BadCredentialsException("La sesión ya no vale"));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BadRequestException("La contraseña actual no es correcta");
        }
        validatePassword(newPassword);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        user.setTokensValidFrom(LocalDateTime.now());
    }

    public static void validatePassword(String password) {
        if (password == null || password.length() < 10) {
            throw new BadRequestException("La contraseña debe tener al menos 10 caracteres");
        }
    }

    public static String normalize(String username) {
        return username == null ? null : username.trim().toLowerCase(Locale.ROOT);
    }
}
