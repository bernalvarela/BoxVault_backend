package com.storagemanager.storage_management.service.storage;

import com.storagemanager.storage_management.config.StorageProperties;
import com.storagemanager.storage_management.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.UUID;

/**
 * Lo que comparten los documentos de un cliente y los de un alquiler: comprobar
 * el fichero, dejarlo en el almacén con una clave única y borrarlo de ahí. Lo
 * que cambia entre unos y otros es a quién pertenece la fila, y eso se queda en
 * sus servicios.
 * <p>
 * La fila y el fichero viven en sitios distintos, así que el orden importa: se
 * sube primero el objeto y sólo después se guarda la fila; si la fila falla se
 * borra el objeto recién subido, para no dejar basura en el almacén. Al borrar
 * se hace al revés (primero la fila), porque un objeto huérfano en el almacén es
 * mucho menos molesto que una ficha que apunta a un fichero que ya no está.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentFiles {

    private final FileStorage fileStorage;
    private final StorageProperties properties;

    /** Lo que hay que guardar en la fila de un fichero ya subido al almacén. */
    public record Stored(String key, String fileName, String contentType, Long sizeBytes) {}

    /**
     * Comprueba el fichero y lo sube bajo {@code prefijo/uuid.ext}. Devuelve los
     * datos de la fila; si guardarla falla, hay que llamar a {@link #safeDelete}
     * con la clave devuelta.
     */
    public Stored store(String keyPrefix, MultipartFile file) {
        validate(file);

        String fileName = cleanFileName(file.getOriginalFilename());
        String key = keyPrefix + "/" + UUID.randomUUID() + extensionOf(fileName);

        try (InputStream in = file.getInputStream()) {
            fileStorage.put(key, file.getContentType(), file.getSize(), in);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el fichero subido", e);
        }
        return new Stored(key, fileName, file.getContentType(), file.getSize());
    }

    public InputStream open(String key) {
        return fileStorage.open(key);
    }

    /** Un fallo borrando el objeto no puede tumbar la operación: se anota y se sigue. */
    public void safeDelete(String key) {
        try {
            fileStorage.delete(key);
        } catch (RuntimeException e) {
            log.warn("No se pudo borrar del almacén el objeto {}; queda huérfano", key, e);
        }
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("No se ha recibido ningún fichero");
        }
        long max = properties.getMaxFileSize().toBytes();
        if (file.getSize() > max) {
            throw new BadRequestException("El fichero ocupa "
                    + (file.getSize() / (1024 * 1024)) + " MB; el máximo son "
                    + properties.getMaxFileSize().toMegabytes() + " MB");
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!properties.getAllowedContentTypes().contains(contentType)) {
            throw new BadRequestException("Tipo de fichero no admitido (" + contentType
                    + "). Se admiten: " + String.join(", ", properties.getAllowedContentTypes()));
        }
    }

    /** El nombre a secas, sin rutas: algunos navegadores mandan la ruta completa. */
    private static String cleanFileName(String original) {
        if (original == null || original.isBlank()) return "documento";
        String name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        if (name.isEmpty()) return "documento";
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    /** La extensión del nombre (con el punto), o vacío; sirve para que la clave se reconozca de un vistazo. */
    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "";
        String ext = fileName.substring(dot).toLowerCase(Locale.ROOT);
        return ext.matches("\\.[a-z0-9]{1,10}") ? ext : "";
    }
}
