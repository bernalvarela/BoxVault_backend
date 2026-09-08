package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.ClientDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClientDocumentRepository extends JpaRepository<ClientDocument, Long> {

    /** Los documentos de un cliente, el último subido primero. */
    List<ClientDocument> findByClientIdOrderByDocumentUploadedAtDescDocumentIdDesc(Long clientId);

    /** La relación con ese documento, sólo si es de ese cliente: la url lleva los dos. */
    Optional<ClientDocument> findByClientIdAndDocumentId(Long clientId, Long documentId);
}
