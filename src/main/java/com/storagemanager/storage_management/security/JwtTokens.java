package com.storagemanager.storage_management.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.storagemanager.storage_management.model.AppUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Emite y valida los tokens, y los mete en sus cookies.
 * <p>
 * Dos tokens: uno de acceso, corto, que acompaña a cada petición, y uno de
 * refresco, largo, que sólo sirve para pedir otro de acceso. Los dos viajan en
 * cookies {@code HttpOnly} + {@code SameSite=Strict}: el navegador las manda
 * solo y JavaScript no puede leerlas, así que un fallo de XSS en la aplicación
 * no se lleva la sesión. Lo que eso deja abierto —CSRF— lo cierra el token
 * antifalsificación de Spring Security (ver SecurityConfig).
 * <p>
 * Firmados con HS256 y un secreto de la configuración. No llevan los permisos
 * dentro: sólo dicen quién eres, y lo que puedes hacer se mira en la base de
 * datos en cada petición. Así quitarle permisos a alguien surte efecto en el
 * acto, en vez de cuando le caduque el token.
 */
@Component
public class JwtTokens {

    /** La cookie que acompaña a cada petición. */
    public static final String ACCESS_COOKIE = "bv_access";
    /** La cookie que sólo vale para /api/auth/refresh. */
    public static final String REFRESH_COOKIE = "bv_refresh";

    private static final String CLAIM_TYPE = "typ";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";
    /** El id del usuario; el "sub" es el nombre, que un administrador podría cambiar. */
    private static final String CLAIM_USER_ID = "uid";

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final SecurityProperties properties;

    public JwtTokens(SecurityProperties properties) {
        this.properties = properties;
        SecretKeySpec key = new SecretKeySpec(properties.secretBytes(), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    public String issueAccessToken(AppUser user) {
        return issue(user, TYPE_ACCESS, properties.getAccessTokenMinutes(), ChronoUnit.MINUTES);
    }

    public String issueRefreshToken(AppUser user) {
        return issue(user, TYPE_REFRESH, properties.getRefreshTokenDays(), ChronoUnit.DAYS);
    }

    private String issue(AppUser user, String type, long amount, ChronoUnit unit) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("boxvault")
                .issuedAt(now)
                .expiresAt(now.plus(amount, unit))
                .subject(user.getUsername())
                .claim(CLAIM_USER_ID, user.getId())
                .claim(CLAIM_TYPE, type)
                .build();
        // La cabecera hay que decirla: sin ella el codificador pide RS256 por
        // defecto y no encuentra clave, porque la nuestra es un secreto (HS256).
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** Descifra y comprueba la firma y la caducidad; lanza si algo no cuadra. */
    public Jwt decode(String token) {
        return decoder.decode(token);
    }

    public static boolean isAccessToken(Jwt jwt) {
        return TYPE_ACCESS.equals(jwt.getClaimAsString(CLAIM_TYPE));
    }

    public static boolean isRefreshToken(Jwt jwt) {
        return TYPE_REFRESH.equals(jwt.getClaimAsString(CLAIM_TYPE));
    }

    public static Long userIdOf(Jwt jwt) {
        Object uid = jwt.getClaim(CLAIM_USER_ID);
        return uid instanceof Number number ? number.longValue() : null;
    }

    // ------------------------------------------------------------------
    // Cookies
    // ------------------------------------------------------------------

    public ResponseCookie accessCookie(String token) {
        return cookie(ACCESS_COOKIE, token, "/", properties.getAccessTokenMinutes() * 60);
    }

    /** Sólo se manda a /api/auth: al resto de la aplicación no le hace falta. */
    public ResponseCookie refreshCookie(String token) {
        return cookie(REFRESH_COOKIE, token, "/api/auth", properties.getRefreshTokenDays() * 24 * 60 * 60);
    }

    /** Las mismas cookies vacías y caducadas, para cerrar la sesión. */
    public ResponseCookie expiredAccessCookie() {
        return cookie(ACCESS_COOKIE, "", "/", 0);
    }

    public ResponseCookie expiredRefreshCookie() {
        return cookie(REFRESH_COOKIE, "", "/api/auth", 0);
    }

    private ResponseCookie cookie(String name, String value, String path, long maxAgeSeconds) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(properties.isSecureCookies())
                .sameSite("Strict")
                .path(path)
                .maxAge(maxAgeSeconds)
                .build();
    }
}
