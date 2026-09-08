package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.ClientDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClientDocumentRepository extends JpaRepository<ClientDocument, Long> {

    /** Los documentos de un cliente, el último subido primero. */
    List<ClientDocument> findByClientIdOrderByUploadedAtDescIdDesc(Long clientId);

    /** El documento sólo si pertenece a ese cliente: la url lleva los dos. */
    Optional<ClientDocument> findByIdAndClientId(Long id, Long clientId);
}
