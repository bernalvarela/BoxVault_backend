package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.TaxFilingDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TaxFilingDocumentRepository extends JpaRepository<TaxFilingDocument, Long> {

    /** Los documentos de una declaración, el último subido primero. */
    List<TaxFilingDocument> findByTaxFilingIdOrderByDocumentUploadedAtDescDocumentIdDesc(Long taxFilingId);

    /** La relación con ese documento, sólo si es de esa declaración: la url lleva las dos. */
    Optional<TaxFilingDocument> findByTaxFilingIdAndDocumentId(Long taxFilingId, Long documentId);
}
