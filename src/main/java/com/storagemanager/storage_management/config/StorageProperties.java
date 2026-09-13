package com.storagemanager.storage_management.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.util.List;

/**
 * Dónde se guardan los ficheros adjuntos ({@code boxvault.storage.*}).
 * <p>
 * Dos implementaciones, una por entorno, igual que la base de datos:
 * <ul>
 *   <li>{@code local} — un directorio del disco. Es lo que se usa en desarrollo,
 *       sin levantar nada más;</li>
 *   <li>{@code s3} — un almacén compatible con S3. En el servidor es RustFS, en
 *       su propio contenedor y sólo en la red interna (ver docker-compose.yml).</li>
 * </ul>
 * En "pro" se activa el modo s3 desde application.yml; las credenciales llegan
 * por variables de entorno y nunca se guardan en el repositorio.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "boxvault.storage")
public class StorageProperties {

    public enum Type { LOCAL, S3 }

    /** Implementación a usar. */
    private Type type = Type.LOCAL;

    /** Directorio raíz del modo local; relativo al directorio de trabajo si no es absoluto. */
    private String localPath = "data/files";

    /** URL del almacén S3 (RustFS: http://rustfs:9000 dentro de la red del compose). */
    private String endpoint;

    /** RustFS no usa regiones, pero el SDK exige una; cualquiera vale mientras no cambie. */
    private String region = "us-east-1";

    /** Bucket donde va todo; se crea solo la primera vez que se sube algo. */
    private String bucket = "boxvault";

    private String accessKey;

    private String secretKey;

    /**
     * URLs de la forma {@code endpoint/bucket/clave} en vez de
     * {@code bucket.endpoint/clave}: obligatorio contra un almacén propio, que no
     * tiene DNS por bucket.
     */
    private boolean pathStyleAccess = true;

    /** Tamaño máximo por fichero. El límite de multipart de Spring va acorde (application.yml). */
    private DataSize maxFileSize = DataSize.ofMegabytes(10);

    /**
     * Tipos de fichero admitidos. Se deja fuera cualquier cosa ejecutable: lo que
     * se archiva son copias de documentos y fotos, no programas.
     */
    private List<String> allowedContentTypes = List.of(
            "application/pdf",
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/heic",
            "image/heif",
            "image/tiff",
            "text/plain");

    /**
     * Extensiones que se admiten aunque el navegador no sepa qué tipo mandar. El
     * fichero de importación del 303 se llama {@code .303}: ningún sistema conoce
     * esa extensión, así que llega como {@code application/octet-stream}. Sólo
     * vale para tipos genéricos, no para colar un ejecutable con otro nombre.
     */
    private List<String> allowedExtensions = List.of(".303", ".txt");

    /** Tipos que un navegador manda cuando no reconoce el fichero. */
    private List<String> genericContentTypes = List.of("", "application/octet-stream", "text/plain");
}
