package com.storagemanager.storage_management.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * La firma de los contratos ({@code boxvault.signing.*}): de dónde salen los
 * enlaces para firmar, cuánto duran, con qué se sella el PDF firmado y quién da
 * fe de la hora.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "boxvault.signing")
public class SigningProperties {

    /**
     * La dirección por la que se entra a BoxVault desde fuera
     * ({@code https://boxvault.duckdns.org}). Vacía: la del navegador que pidió
     * el enlace.
     */
    private String publicUrl = "";

    /** Lo que vale un enlace para firmar desde que se pide. */
    private Duration linkTtl = Duration.ofDays(7);

    /** Lo que se puede descargar la copia firmada con el mismo enlace. */
    private Duration copyTtl = Duration.ofDays(30);

    /** La contraseña del almacén PKCS12 con el certificado del sello. */
    private String keystorePassword = "";

    /** Si se pide sello de tiempo a un tercero al sellar. */
    private boolean tsaEnabled = true;

    /** El servidor de sellos de tiempo (RFC 3161). */
    private String tsaUrl = "https://freetsa.org/tsr";
}
