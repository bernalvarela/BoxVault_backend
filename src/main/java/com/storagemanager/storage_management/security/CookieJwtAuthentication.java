package com.storagemanager.storage_management.security;

import com.storagemanager.storage_management.model.AppUser;
import com.storagemanager.storage_management.repository.AppUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * De la cookie al usuario de la petición.
 * <p>
 * {@link CookieToAuthorizationHeader} coge el token de acceso de su cookie y lo
 * presenta como una cabecera {@code Authorization: Bearer ...}, que es de donde
 * lo lee Spring Security. Y {@link Converter} convierte el token ya validado en
 * el {@link CurrentUser} que ve el resto de la aplicación.
 * <p>
 * Lo del filtro no es un rodeo caprichoso, y conviene no "simplificarlo": la
 * alternativa evidente —un {@code bearerTokenResolver} que lea la cookie— tiene
 * un efecto que no se ve. El configurador de resource server añade a la lista de
 * exenciones de CSRF un comparador que pregunta a ese resolver si la petición
 * lleva token; con el resolver leyendo la cookie, TODAS las peticiones
 * autenticadas quedaban exentas y la protección CSRF no se aplicaba nunca.
 * Tiene su lógica cuando el token va en una cabecera, porque el navegador no la
 * pone solo; con el token en una cookie, que sí manda solo, deja la puerta
 * abierta. Poniendo la cabecera en un filtro posterior al CsrfFilter, éste ve la
 * petición tal como llegó —sin Authorization— y protege como debe.
 * <p>
 * La conversión pasa por la base de datos a propósito: comprueba que el usuario
 * siga de alta y que el token se emitiera después de su {@code tokensValidFrom},
 * y así cambiar una contraseña o dar de baja a alguien echa abajo sus sesiones
 * al momento.
 */
public final class CookieJwtAuthentication {

    private CookieJwtAuthentication() {}

    /**
     * Copia el token de la cookie a la cabecera Authorization. Se coloca después
     * del CsrfFilter y antes del que autentica (ver SecurityConfig).
     */
    @Component
    public static class CookieToAuthorizationHeader extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String token = accessCookie(request);
            if (token == null || request.getHeader(HttpHeaders.AUTHORIZATION) != null) {
                chain.doFilter(request, response);
                return;
            }
            chain.doFilter(new BearerHeaderRequest(request, token), response);
        }

        private static String accessCookie(HttpServletRequest request) {
            Cookie[] cookies = request.getCookies();
            if (cookies == null) return null;
            for (Cookie cookie : cookies) {
                if (JwtTokens.ACCESS_COOKIE.equals(cookie.getName()) && !cookie.getValue().isBlank()) {
                    return cookie.getValue();
                }
            }
            return null;
        }

        /** La misma petición, más la cabecera Authorization sacada de la cookie. */
        private static final class BearerHeaderRequest extends HttpServletRequestWrapper {
            private final String bearer;

            BearerHeaderRequest(HttpServletRequest request, String token) {
                super(request);
                this.bearer = "Bearer " + token;
            }

            @Override
            public String getHeader(String name) {
                return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name) ? bearer : super.getHeader(name);
            }

            @Override
            public Enumeration<String> getHeaders(String name) {
                return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)
                        ? Collections.enumeration(List.of(bearer))
                        : super.getHeaders(name);
            }
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
