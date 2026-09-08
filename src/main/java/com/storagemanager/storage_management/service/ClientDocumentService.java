package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.dto.ClientDocumentDTO;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.Client;
import com.storagemanager.storage_management.model.ClientDocument;
import com.storagemanager.storage_management.model.Document;
import com.storagemanager.storage_management.model.enums.DocumentType;
import com.storagemanager.storage_management.repository.ClientDocumentRepository;
import com.storagemanager.storage_management.repository.ClientRepository;
import com.storagemanager.storage_management.security.UnitScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Los documentos archivados en la ficha de un cliente: lo que es suyo y no de un
 * alquiler concreto (el DNI, un contrato de trabajo, una nómina, una foto). La
 * copia firmada del contrato de alquiler cuelga del alquiler; ver
 * {@link RentalDocumentService}.
 * <p>
 * Este servicio sólo lleva la relación cliente–documento: del fichero se encarga
 * {@link DocumentService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClientDocumentService {

    private final ClientDocumentRepository clientDocumentRepository;
    private final ClientRepository clientRepository;
    private final DocumentService documents;
    private final UnitScope unitScope;

    public List<ClientDocumentDTO> getDocuments(Long clientId) {
        requireClient(clientId);
        return clientDocumentRepository.findByClientIdOrderByDocumentUploadedAtDescDocumentIdDesc(clientId).stream()
                .map(ClientDocumentDTO::of)
                .toList();
    }

    @Transactional
    public ClientDocumentDTO upload(Long clientId, MultipartFile file, DocumentType type, String description) {
        Client client = requireClient(clientId);
        Document document = documents.store(
                "clients/" + clientId, file, requireClientType(type), description);

        try {
            ClientDocument saved = clientDocumentRepository.save(ClientDocument.builder()
                    .client(client)
                    .document(document)
                    .build());
            return ClientDocumentDTO.of(saved);
        } catch (RuntimeException e) {
            // Sin relación, el documento no es de nadie: no puede quedarse.
            documents.delete(document);
            throw e;
        }
    }

    public DocumentService.Content download(Long clientId, Long documentId) {
        return documents.open(requireLink(clientId, documentId).getDocument());
    }

    @Transactional
    public void delete(Long clientId, Long documentId) {
        ClientDocument link = requireLink(clientId, documentId);
        clientDocumentRepository.delete(link);
        documents.delete(link.getDocument());
    }

    /**
     * Borra los documentos de un cliente que se va a borrar. Lo llama
     * {@link ClientService}: sin esto la fila del cliente no se podría borrar (la
     * clave ajena) y los ficheros se quedarían en el almacén para siempre.
     */
    @Transactional
    public void deleteByClient(Long clientId) {
        List<ClientDocument> links =
                clientDocumentRepository.findByClientIdOrderByDocumentUploadedAtDescDocumentIdDesc(clientId);
        clientDocumentRepository.deleteAll(links);
        links.forEach(link -> documents.delete(link.getDocument()));
    }

    /**
     * En la ficha sólo cabe lo que es del cliente. El caso que importa es
     * CONTRATO_ALQUILER: el contrato va en su alquiler, y aceptarlo aquí sería
     * volver a repartir el mismo documento por dos sitios.
     */
    private DocumentType requireClientType(DocumentType type) {
        if (type == null) return DocumentType.OTRO;
        if (!DocumentType.forClient().contains(type)) {
            throw new BadRequestException("Un documento de tipo " + type
                    + " no va en la ficha del cliente, sino en el alquiler al que pertenece");
        }
        return type;
    }

    /**
     * El cliente, comprobando además que esté en el ámbito del usuario: la ficha
     * se protege en {@code ClientService}, y sus documentos —que son lo delicado,
     * un DNI escaneado— tienen que ir por la misma puerta.
     */
    private Client requireClient(Long clientId) {
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ResourceNotFoundException("Client not found with id: " + clientId));
        unitScope.requireClientVisible(clientId);
        return client;
    }

    /** Un documento de ese cliente; comprueba primero que el cliente sea suyo. */
    private ClientDocument requireLink(Long clientId, Long documentId) {
        requireClient(clientId);
        return clientDocumentRepository.findByClientIdAndDocumentId(clientId, documentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Document " + documentId + " not found for client " + clientId));
    }
}
