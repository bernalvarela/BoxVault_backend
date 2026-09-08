package com.storagemanager.storage_management.security;

import com.storagemanager.storage_management.exception.BadRequestException;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;

/**
 * La configuración de la sesión ({@code boxvault.security.*}).
 * <p>
 * El secreto de firma no tiene valor por defecto en el servidor: llega por
 * variable de entorno, como las claves de RustFS, y nunca se guarda en el
 * repositorio. En desarrollo sí hay uno fijo, para no tener que inventarse nada
 * al arrancar en local.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "boxvault.security")
public class SecurityProperties {

    /** Secreto HS256. Mínimo 32 caracteres: es lo que exige el algoritmo. */
    private String jwtSecret;

    /** Cuánto vale el token de acceso; se renueva solo con el de refresco. */
    private long accessTokenMinutes = 15;

    /** Cuánto dura la sesión sin volver a escribir la contraseña. */
    private long refreshTokenDays = 14;

    /**
     * Cookies sólo por HTTPS. En el servidor sí (Traefik termina el TLS); en
     * desarrollo no, que se entra por http://localhost.
     */
    private boolean secureCookies = true;

    /** El administrador que se crea al arrancar si no hay ningún usuario. */
    private String bootstrapUsername = "admin";

    private String bootstrapPassword;

    byte[] secretBytes() {
        if (jwtSecret == null || jwtSecret.length() < 32) {
            throw new IllegalStateException("boxvault.security.jwt-secret es obligatorio y debe tener al menos "
                    + "32 caracteres. Llega por la variable BOXVAULT_JWT_SECRET (genera una con: "
                    + "openssl rand -hex 32). Si ya está en el .env del servidor y sigue faltando, es que "
                    + "docker-compose.yml no la pasa al contenedor: el .env sólo lo lee Compose, y la "
                    + "variable tiene que estar además en el bloque environment: del servicio boxvault. "
                    + (jwtSecret == null || jwtSecret.isBlank()
                            ? "Ahora mismo llega vacía."
                            : "Ahora mismo llegan sólo " + jwtSecret.length() + " caracteres, quizá porque "
                              + "el valor lleva un '$' y Compose se lo ha comido."));
        }
        return jwtSecret.getBytes(StandardCharsets.UTF_8);
    }

    /** La contraseña inicial del administrador, comprobando que la hayan puesto. */
    String requireBootstrapPassword() {
        if (bootstrapPassword == null || bootstrapPassword.isBlank()) {
            throw new BadRequestException("No hay ningún usuario y no se ha configurado "
                    + "boxvault.security.bootstrap-password (BOXVAULT_ADMIN_PASSWORD)");
        }
        return bootstrapPassword;
    }
}
