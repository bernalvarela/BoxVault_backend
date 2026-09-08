package com.storagemanager.storage_management.service.storage;

import java.io.InputStream;

/**
 * El almacén de ficheros, visto por la aplicación: guardar, leer y borrar un
 * objeto por su clave. Nada más entra aquí, para que cambiar de almacén (disco
 * en desarrollo, RustFS por S3 en el servidor) no toque nada del dominio.
 */
public interface FileStorage {

    /**
     * Guarda (o reemplaza) el objeto de esa clave. No cierra {@code content}:
     * lo cierra quien lo abrió.
     */
    void put(String key, String contentType, long sizeBytes, InputStream content);

    /** Abre el objeto para leerlo. El que llama es responsable de cerrarlo. */
    InputStream open(String key);

    /** Borra el objeto; no falla si ya no está. */
    void delete(String key);
}
