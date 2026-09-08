package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.RentalDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RentalDocumentRepository extends JpaRepository<RentalDocument, Long> {

    /** Los documentos de un alquiler, el último subido primero. */
    List<RentalDocument> findByRentalAgreementIdOrderByDocumentUploadedAtDescDocumentIdDesc(Long rentalAgreementId);

    /** La relación con ese documento, sólo si es de ese alquiler: la url lleva los dos. */
    Optional<RentalDocument> findByRentalAgreementIdAndDocumentId(Long rentalAgreementId, Long documentId);
}
