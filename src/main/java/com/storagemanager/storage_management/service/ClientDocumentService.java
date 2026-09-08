package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.config.StorageProperties;
import com.storagemanager.storage_management.dto.ClientDocumentDTO;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.Client;
import com.storagemanager.storage_management.model.ClientDocument;
import com.storagemanager.storage_management.model.enums.DocumentType;
import com.storagemanager.storage_management.repository.ClientDocumentRepository;
import com.storagemanager.storage_management.repository.ClientRepository;
import com.storagemanager.storage_management.service.storage.FileStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Los documentos archivados en la ficha de un cliente.
 * <p>
 * La fila y el fichero viven en sitios distintos, así que el orden importa: se
 * sube primero el objeto y sólo después se guarda la fila; si la fila falla se
 * borra el objeto recién subido, para no dejar basura en el almacén. Al borrar
 * se hace al revés (primero la fila), porque un objeto huérfano en el almacén es
 * mucho menos molesto que una ficha que apunta a un fichero que ya no está.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClientDocumentService {

    private final ClientDocumentRepository documentRepository;
    private final ClientRepository clientRepository;
    private final FileStorage fileStorage;
    private final StorageProperties properties;

    /** El contenido de un documento, para servirlo: la ficha y el fichero abierto. */
    public record Content(ClientDocument document, InputStream stream) {}

    public List<ClientDocumentDTO> getDocuments(Long clientId) {
        requireClient(clientId);
        return documentRepository.findByClientIdOrderByUploadedAtDescIdDesc(clientId).stream()
                .map(ClientDocumentDTO::of)
                .toList();
    }

    @Transactional
    public ClientDocumentDTO upload(Long clientId, MultipartFile file, DocumentType type, String description) {
        Client client = requireClient(clientId);
        validate(file);

        String fileName = cleanFileName(file.getOriginalFilename());
        String key = "clients/" + clientId + "/" + UUID.randomUUID() + extensionOf(fileName);

        try (InputStream in = file.getInputStream()) {
            fileStorage.put(key, file.getContentType(), file.getSize(), in);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el fichero subido", e);
        }

        try {
            ClientDocument saved = documentRepository.save(ClientDocument.builder()
                    .client(client)
                    .documentType(type != null ? type : DocumentType.OTRO)
                    .fileName(fileName)
                    .contentType(file.getContentType())
                    .sizeBytes(file.getSize())
                    .storageKey(key)
                    .description(description)
                    .build());
            return ClientDocumentDTO.of(saved);
        } catch (RuntimeException e) {
            // La ficha no se guardó: el objeto subido ya no lo referencia nadie.
            safeDelete(key);
            throw e;
        }
    }

    public Content download(Long clientId, Long documentId) {
        ClientDocument document = requireDocument(clientId, documentId);
        return new Content(document, fileStorage.open(document.getStorageKey()));
    }

    @Transactional
    public void delete(Long clientId, Long documentId) {
        ClientDocument document = requireDocument(clientId, documentId);
        documentRepository.delete(document);
        safeDelete(document.getStorageKey());
    }

    /**
     * Borra los documentos de un cliente que se va a borrar. Lo llama
     * {@link ClientService}: sin esto la fila del cliente no se podría borrar (la
     * clave ajena) y los ficheros se quedarían en el almacén para siempre.
     */
    @Transactional
    public void deleteByClient(Long clientId) {
        List<ClientDocument> documents = documentRepository.findByClientIdOrderByUploadedAtDescIdDesc(clientId);
        documentRepository.deleteAll(documents);
        documents.forEach(d -> safeDelete(d.getStorageKey()));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Client requireClient(Long clientId) {
        return clientRepository.findById(clientId)
                .orElseThrow(() -> new ResourceNotFoundException("Client not found with id: " + clientId));
    }

    private ClientDocument requireDocument(Long clientId, Long documentId) {
        return documentRepository.findByIdAndClientId(documentId, clientId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Document " + documentId + " not found for client " + clientId));
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

    /** Un fallo borrando el objeto no puede tumbar la operación: se anota y se sigue. */
    private void safeDelete(String key) {
        try {
            fileStorage.delete(key);
        } catch (RuntimeException e) {
            log.warn("No se pudo borrar del almacén el objeto {}; queda huérfano", key, e);
        }
    }
}
