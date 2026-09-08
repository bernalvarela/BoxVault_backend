package com.storagemanager.storage_management.controller;

import com.storagemanager.storage_management.dto.AuthDTOs.ChangePasswordRequest;
import com.storagemanager.storage_management.dto.AuthDTOs.LoginRequest;
import com.storagemanager.storage_management.dto.AuthDTOs.MeResponse;
import com.storagemanager.storage_management.model.AppUser;
import com.storagemanager.storage_management.security.Authenticated;
import com.storagemanager.storage_management.security.CurrentUser;
import com.storagemanager.storage_management.security.JwtTokens;
import com.storagemanager.storage_management.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;

/**
 * La sesión: entrar, renovarla, salir y saber quién soy.
 * <p>
 * Los tokens no se devuelven en el cuerpo: van en cookies {@code HttpOnly}, así
 * que el frontend nunca los toca. Entrar y renovar son las dos únicas rutas
 * abiertas sin autenticar.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final JwtTokens tokens;

    @PostMapping("/login")
    public ResponseEntity<MeResponse> login(@Valid @RequestBody LoginRequest request) {
        AppUser user = authService.login(request.username(), request.password());
        return withSessionCookies(user, MeResponse.of(CurrentUser.of(user)));
    }

    /**
     * Un token de acceso nuevo a partir del de refresco. Es lo que hace que la
     * sesión dure días sin que el token que viaja en cada petición dure más de
     * unos minutos.
     */
    @PostMapping("/refresh")
    public ResponseEntity<MeResponse> refresh(HttpServletRequest request) {
        String refreshToken = cookieValue(request, JwtTokens.REFRESH_COOKIE);
        if (refreshToken == null) {
            throw new BadCredentialsException("No hay sesión que renovar");
        }
        Jwt jwt;
        try {
            jwt = tokens.decode(refreshToken);
        } catch (RuntimeException e) {
            throw new BadCredentialsException("La sesión ha caducado");
        }
        if (!JwtTokens.isRefreshToken(jwt)) {
            throw new BadCredentialsException("Ese token no sirve para renovar");
        }
        AppUser user = authService.activeUser(JwtTokens.userIdOf(jwt));
        return withSessionCookies(user, MeResponse.of(CurrentUser.of(user)));
    }

    /** Salir: las cookies se sustituyen por otras ya caducadas. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, tokens.expiredAccessCookie().toString())
                .header(HttpHeaders.SET_COOKIE, tokens.expiredRefreshCookie().toString())
                .build();
    }

    /** Quién soy y qué puedo hacer; con esto el frontend monta su menú. */
    @GetMapping("/me")
    public ResponseEntity<MeResponse> me() {
        return ResponseEntity.ok(MeResponse.of(Authenticated.user()));
    }

    /**
     * Cambiar la propia contraseña. No pide permiso ninguno: es de uno mismo, y
     * además es lo que tiene que poder hacer quien entra con una contraseña
     * puesta por un administrador.
     */
    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changeOwnPassword(Authenticated.user(), request.currentPassword(), request.newPassword());
        // La sesión de este mismo navegador se renueva; las demás se caen.
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<MeResponse> withSessionCookies(AppUser user, MeResponse body) {
        ResponseCookie access = tokens.accessCookie(tokens.issueAccessToken(user));
        ResponseCookie refresh = tokens.refreshCookie(tokens.issueRefreshToken(user));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, access.toString())
                .header(HttpHeaders.SET_COOKIE, refresh.toString())
                .body(body);
    }

    private static String cookieValue(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies())
                .filter(c -> name.equals(c.getName()) && !c.getValue().isBlank())
                .map(jakarta.servlet.http.Cookie::getValue)
                .findFirst()
                .orElse(null);
    }
}
