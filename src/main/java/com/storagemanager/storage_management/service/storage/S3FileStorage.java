package com.storagemanager.storage_management.service.storage;

import com.storagemanager.storage_management.config.StorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.InputStream;

/**
 * Almacén sobre un servicio compatible con S3; en el servidor, RustFS.
 * <p>
 * El bucket se comprueba (y se crea si hace falta) la primera vez que se usa, no
 * al arrancar: si el almacén todavía no está listo, la aplicación arranca igual
 * y sólo fallan las subidas, no el resto de BoxVault.
 */
@Slf4j
@RequiredArgsConstructor
public class S3FileStorage implements FileStorage {

    private final S3Client s3;
    private final StorageProperties properties;

    private volatile boolean bucketReady = false;

    private String bucket() {
        return properties.getBucket();
    }

    /** Crea el bucket si no existe. Idempotente y sólo efectiva una vez. */
    private synchronized void ensureBucket() {
        if (bucketReady) return;
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket()).build());
        } catch (NoSuchBucketException e) {
            log.info("El bucket '{}' no existe en {}: se crea", bucket(), properties.getEndpoint());
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket()).build());
        } catch (S3Exception e) {
            if (e.statusCode() != 404) throw e;
            log.info("El bucket '{}' no existe en {}: se crea", bucket(), properties.getEndpoint());
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket()).build());
        }
        bucketReady = true;
    }

    @Override
    public void put(String key, String contentType, long sizeBytes, InputStream content) {
        ensureBucket();
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket())
                .key(key)
                .contentType(contentType)
                .contentLength(sizeBytes)
                .build();
        s3.putObject(request, RequestBody.fromInputStream(content, sizeBytes));
    }

    @Override
    public InputStream open(String key) {
        ensureBucket();
        try {
            return s3.getObject(GetObjectRequest.builder().bucket(bucket()).key(key).build());
        } catch (NoSuchKeyException e) {
            throw new IllegalStateException("El fichero ya no está en el almacén: " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        ensureBucket();
        // S3 no distingue borrar lo que no existe: la llamada es idempotente de suyo.
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket()).key(key).build());
    }
}
