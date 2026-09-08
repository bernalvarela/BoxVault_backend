package com.storagemanager.storage_management.config;

import com.storagemanager.storage_management.service.storage.FileStorage;
import com.storagemanager.storage_management.service.storage.LocalFileStorage;
import com.storagemanager.storage_management.service.storage.S3FileStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

/**
 * Elige el almacén de ficheros según {@code boxvault.storage.type}: el disco en
 * desarrollo (por defecto) y S3 —RustFS— en el servidor.
 * <p>
 * El cliente de S3 se monta a mano, sin la cadena de credenciales por defecto
 * del SDK: las claves vienen de la configuración y no se busca ni perfil de AWS,
 * ni variables de entorno, ni servicio de metadatos. Detalles que importan
 * apuntando a un almacén propio:
 * <ul>
 *   <li>cliente HTTP {@code UrlConnectionHttpClient}: usa el HTTP del JDK, sin
 *       Netty ni Apache, que es lo que la imagen nativa lleva mejor;</li>
 *   <li>URLs "path style" ({@code endpoint/bucket/clave}): un almacén propio no
 *       tiene un DNS por bucket;</li>
 *   <li>checksums sólo cuando la operación los exige: desde la versión 2.30 el
 *       SDK los añade por defecto a todas las subidas y no todos los servicios
 *       compatibles con S3 los aceptan.</li>
 * </ul>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

    /**
     * El almacén, elegido <em>al arrancar</em>. La decisión no puede ser una
     * {@code @ConditionalOnProperty}: el binario nativo se compila con AOT, que
     * evalúa las condiciones al construir la imagen —sin perfil, o sea "local"—
     * y las deja congeladas, de modo que el {@code type: s3} del perfil "pro" no
     * llegaría a aplicarse nunca. Con un solo bean y un {@code if} las dos
     * implementaciones entran en la imagen y manda la configuración de verdad.
     */
    @Bean
    public FileStorage fileStorage(StorageProperties properties) {
        if (properties.getType() == StorageProperties.Type.S3) {
            log.info("Ficheros adjuntos en {} (bucket '{}')", properties.getEndpoint(), properties.getBucket());
            return new S3FileStorage(s3Client(properties), properties);
        }
        return new LocalFileStorage(properties.getLocalPath());
    }

    private S3Client s3Client(StorageProperties properties) {
        if (properties.getEndpoint() == null || properties.getEndpoint().isBlank()) {
            throw new IllegalStateException("boxvault.storage.endpoint es obligatorio con boxvault.storage.type=s3");
        }
        return S3Client.builder()
                .endpointOverride(URI.create(properties.getEndpoint()))
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey())))
                .forcePathStyle(properties.isPathStyleAccess())
                .httpClient(UrlConnectionHttpClient.create())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
    }

}
