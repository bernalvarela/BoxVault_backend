package com.storagemanager.storage_management.service.storage;

import org.springframework.core.io.InputStreamSource;

import java.io.InputStream;

/**
 * El almacén de ficheros, visto por la aplicación: guardar, leer y borrar un
 * objeto por su clave. Nada más entra aquí, para que cambiar de almacén (disco
 * en desarrollo, RustFS por S3 en el servidor) no toque nada del dominio.
 */
public interface FileStorage {

    /**
     * Guarda (o reemplaza) el objeto de esa clave. Recibe de dónde <em>abrir</em> el
     * contenido, no un stream ya abierto: el almacén puede necesitar leerlo más de
     * una vez (el cliente de S3 lo recorre para firmar la petición y otra vez para
     * enviarla, y de nuevo si reintenta), y el stream de un fichero subido sólo se
     * puede leer una. {@code MultipartFile} ya es un {@link InputStreamSource}; para
     * unos bytes, {@code new ByteArrayResource(bytes)}. Cada stream que abre, lo
     * cierra el almacén.
     */
    void put(String key, String contentType, long sizeBytes, InputStreamSource content);

    /** Abre el objeto para leerlo. El que llama es responsable de cerrarlo. */
    InputStream open(String key);

    /** Borra el objeto; no falla si ya no está. */
    void delete(String key);
}
