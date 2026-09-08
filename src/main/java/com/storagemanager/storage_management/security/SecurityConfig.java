package com.storagemanager.storage_management.security;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * Cómo se protege la API.
 * <ul>
 *   <li>sin sesión de servidor: cada petición lleva su token de acceso en la
 *       cookie {@code bv_access}, que valida Spring Security como cualquier JWT;</li>
 *   <li>como el token viaja en una cookie, el navegador lo manda solo y hay que
 *       cubrir el CSRF: token antifalsificación en la cookie {@code XSRF-TOKEN},
 *       que axios devuelve en la cabecera {@code X-XSRF-TOKEN} sin que haya que
 *       programar nada (lo hace de serie);</li>
 *   <li>lo que puede hacer cada uno no se decide aquí sino en cada controlador,
 *       con {@code @PreAuthorize("@access.can(...)")}: las rutas son muchas y el
 *       permiso va con la operación, no con la url;</li>
 *   <li>el frontend (index.html y sus ficheros) se sirve sin autenticar: es una
 *       aplicación de una sola página, y quien decide si se entra o no es la
 *       propia pantalla de login contra {@code /api/auth/me}.</li>
 * </ul>
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties.class)
@RequiredArgsConstructor
public class SecurityConfig {

    private final CookieJwtAuthentication.CookieToAuthorizationHeader cookieToAuthorizationHeader;
    private final CookieJwtAuthentication.Converter jwtAuthenticationConverter;

    @Bean
    public PasswordEncoder passwordEncoder() {
        // BCrypt, con el prefijo {bcrypt} delante: si algún día se cambia de
        // algoritmo, las contraseñas viejas se siguen pudiendo comprobar.
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** El decodificador que valida firma y caducidad; las claves las lleva JwtTokens. */
    @Bean
    public JwtDecoder jwtDecoder(JwtTokens tokens) {
        return tokens::decode;
    }

    /**
     * Dónde vive el token antifalsificación: una cookie que JavaScript sí puede
     * leer —tiene que poder, para devolverla en la cabecera— y que no es la
     * sesión. Es un bean porque el login también lo usa: entrar está exento de
     * CSRF (el token todavía no existe), así que es ahí donde hay que crearlo,
     * en vez de esperar a que lo cree la primera lectura.
     */
    @Bean
    public CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie.sameSite("Strict"));
        return repository;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, CookieCsrfTokenRepository csrfRepository)
            throws Exception {
        // El token va tal cual en la cabecera; es lo que manda axios.
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);

        http
            .csrf(csrf -> csrf
                    .csrfTokenRepository(csrfRepository)
                    .csrfTokenRequestHandler(csrfHandler)
                    // Entrar y renovar no cambian datos de nadie y llegan antes de
                    // que exista el token antifalsificación.
                    .ignoringRequestMatchers("/api/auth/login", "/api/auth/refresh"))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                    // La aplicación web y sus recursos: la pantalla de login vive aquí.
                    .requestMatchers("/", "/index.html", "/favicon.ico", "/assets/**", "/vite.svg").permitAll()
                    .requestMatchers("/api/auth/login", "/api/auth/refresh", "/api/auth/logout").permitAll()
                    .requestMatchers("/api/**").authenticated()
                    // Cualquier otra ruta la resuelve el index.html de la SPA.
                    .anyRequest().permitAll())
            // El token pasa de la cookie a la cabecera Authorization aquí, ya
            // pasado el CsrfFilter: así la protección CSRF sigue aplicándose (ver
            // CookieJwtAuthentication, que explica por qué importa el orden).
            .addFilterBefore(cookieToAuthorizationHeader, BearerTokenAuthenticationFilter.class)
            .oauth2ResourceServer(oauth -> oauth
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                    .authenticationEntryPoint((request, response, ex) ->
                            write(response, HttpStatus.UNAUTHORIZED, "Hay que iniciar sesión"))
                    .accessDeniedHandler((request, response, ex) ->
                            write(response, HttpStatus.FORBIDDEN, "No tienes permiso para esto")))
            .exceptionHandling(handling -> handling
                    .authenticationEntryPoint((request, response, ex) ->
                            write(response, HttpStatus.UNAUTHORIZED, "Hay que iniciar sesión"))
                    .accessDeniedHandler((request, response, ex) ->
                            write(response, HttpStatus.FORBIDDEN, "No tienes permiso para esto")));

        return http.build();
    }

    /**
     * 401 y 403 con la misma forma que los demás errores (ver
     * GlobalExceptionHandler). El JSON se escribe a mano y no con Jackson: son
     * cuatro campos fijos, sin nada que venga de fuera, y así esto no depende de
     * qué versión de Jackson lleve Spring Boot.
     */
    private void write(HttpServletResponse response, HttpStatus status, String message) throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("""
                {"timestamp":"%s","status":%d,"error":"%s","message":"%s"}"""
                .formatted(LocalDateTime.now(), status.value(), status.getReasonPhrase(), message));
    }
}
