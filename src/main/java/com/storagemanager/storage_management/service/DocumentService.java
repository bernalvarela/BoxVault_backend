package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.model.Document;
import com.storagemanager.storage_management.model.enums.DocumentType;
import com.storagemanager.storage_management.repository.DocumentRepository;
import com.storagemanager.storage_management.service.storage.DocumentFiles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

/**
 * El fichero archivado en sí, sin saber de quién es: guardarlo, abrirlo y
 * borrarlo. De quién es lo llevan los servicios de cada dueño
 * ({@link ClientDocumentService}, {@link RentalDocumentService}), que son los
 * que manejan su tabla de relación.
 * <p>
 * La fila y el fichero viven en sitios distintos, así que el orden importa: se
 * sube primero el objeto y sólo después se guarda la fila; si algo falla se
 * borra el objeto, para no dejar basura en el almacén. Al borrar se hace al
 * revés (primero las filas), porque un objeto huérfano en el almacén es mucho
 * menos molesto que una ficha que apunta a un fichero que ya no está.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final DocumentFiles files;

    /** El contenido de un documento, para servirlo: la ficha y el fichero abierto. */
    public record Content(Document document, InputStream stream) {}

    /**
     * Sube el fichero y guarda su ficha. El que llame tiene que crear después la
     * relación con su dueño y, si eso falla, deshacerlo con {@link #delete}.
     */
    @Transactional
    public Document store(String keyPrefix, MultipartFile file, DocumentType type, String description) {
        DocumentFiles.Stored stored = files.store(keyPrefix, file);
        try {
            return documentRepository.save(Document.builder()
                    .documentType(type != null ? type : DocumentType.OTRO)
                    .fileName(stored.fileName())
                    .contentType(stored.contentType())
                    .sizeBytes(stored.sizeBytes())
                    .storageKey(stored.key())
                    .description(description)
                    .build());
        } catch (RuntimeException e) {
            // La ficha no se guardó: el objeto subido ya no lo referencia nadie.
            files.safeDelete(stored.key());
            throw e;
        }
    }

    public Content open(Document document) {
        return new Content(document, files.open(document.getStorageKey()));
    }

    /** Borra la ficha y el fichero. Las relaciones tienen que estar ya borradas. */
    @Transactional
    public void delete(Document document) {
        documentRepository.delete(document);
        files.safeDelete(document.getStorageKey());
    }
}
