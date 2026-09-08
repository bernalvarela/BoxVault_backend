package com.storagemanager.storage_management.service.storage;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Almacén sobre el disco, para desarrollo: cada objeto es un fichero bajo un
 * directorio raíz, con la misma clave que tendría en S3. Evita tener que
 * levantar RustFS para trabajar en local, igual que H2 evita PostgreSQL.
 */
@Slf4j
public class LocalFileStorage implements FileStorage {

    private final Path root;

    public LocalFileStorage(String rootPath) {
        this.root = Path.of(rootPath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo crear el directorio de ficheros " + root, e);
        }
        log.info("Ficheros adjuntos en disco, bajo {}", root);
    }

    /** La ruta de una clave, comprobando que no se salga del directorio raíz. */
    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Clave de fichero fuera del almacén: " + key);
        }
        return path;
    }

    @Override
    public void put(String key, String contentType, long sizeBytes, InputStream content) {
        Path path = resolve(key);
        try {
            Files.createDirectories(path.getParent());
            Files.copy(content, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo guardar el fichero " + key, e);
        }
    }

    @Override
    public InputStream open(String key) {
        try {
            return Files.newInputStream(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el fichero " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo borrar el fichero " + key, e);
        }
    }
}
