package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.TaxNoticeDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TaxNoticeDocumentRepository extends JpaRepository<TaxNoticeDocument, Long> {

    /** Los documentos de una notificación, el último subido primero. */
    List<TaxNoticeDocument> findByTaxNoticeIdOrderByDocumentUploadedAtDescDocumentIdDesc(Long taxNoticeId);

    /** La relación con ese documento, sólo si es de esa notificación: la url lleva las dos. */
    Optional<TaxNoticeDocument> findByTaxNoticeIdAndDocumentId(Long taxNoticeId, Long documentId);

    long countByTaxNoticeId(Long taxNoticeId);
}
