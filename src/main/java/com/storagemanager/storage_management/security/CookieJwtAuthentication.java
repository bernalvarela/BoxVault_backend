package com.storagemanager.storage_management.security;

import com.storagemanager.storage_management.model.AppUser;
import com.storagemanager.storage_management.repository.AppUserRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * De la cookie al usuario de la petición.
 * <p>
 * {@link Resolver} saca el token de acceso de su cookie (no de la cabecera
 * Authorization, que es de donde lo saca Spring por defecto), y
 * {@link Converter} convierte el token ya validado en el {@link CurrentUser} que
 * ve el resto de la aplicación. La conversión pasa por la base de datos a
 * propósito: comprueba que el usuario siga de alta y que el token se emitiera
 * después de su {@code tokensValidFrom}, y así cambiar una contraseña o dar de
 * baja a alguien echa abajo sus sesiones al momento.
 */
public final class CookieJwtAuthentication {

    private CookieJwtAuthentication() {}

    /** El token de acceso viaja en su cookie, no en una cabecera. */
    @Component
    public static class Resolver implements BearerTokenResolver {
        @Override
        public String resolve(HttpServletRequest request) {
            Cookie[] cookies = request.getCookies();
            if (cookies == null) return null;
            for (Cookie cookie : cookies) {
                if (JwtTokens.ACCESS_COOKIE.equals(cookie.getName()) && !cookie.getValue().isBlank()) {
                    return cookie.getValue();
                }
            }
            return null;
        }
    }

    /** El token válido, convertido en el usuario que hay detrás. */
    @Component
    @RequiredArgsConstructor
    public static class Converter
            implements org.springframework.core.convert.converter.Converter<Jwt, AbstractAuthenticationToken> {

        private final AppUserRepository users;

        @Override
        public AbstractAuthenticationToken convert(Jwt jwt) {
            if (!JwtTokens.isAccessToken(jwt)) {
                // El de refresco sólo vale en /api/auth/refresh, y allí no se pasa por aquí.
                throw new InvalidBearerTokenException("El token no es de acceso");
            }
            Long userId = JwtTokens.userIdOf(jwt);
            AppUser user = userId == null ? null : users.findById(userId).orElse(null);
            if (user == null || !user.isActive()) {
                throw new InvalidBearerTokenException("El usuario ya no existe o está de baja");
            }
            // El "iat" de un JWT va en segundos enteros, así que se compara con la
            // marca del usuario truncada igual: si no, un token emitido en el mismo
            // segundo en que se puso la marca (entrar justo después de crear el
            // usuario o de cambiar la contraseña) parecería anterior y no valdría.
            Instant validFrom = user.getTokensValidFrom()
                    .atZone(ZoneId.systemDefault()).toInstant().truncatedTo(ChronoUnit.SECONDS);
            if (jwt.getIssuedAt() != null && jwt.getIssuedAt().isBefore(validFrom)) {
                throw new InvalidBearerTokenException("La sesión ya no vale; hay que volver a entrar");
            }
            return new UsernamePasswordAuthenticationToken(CurrentUser.of(user), jwt, List.of());
        }
    }
}
