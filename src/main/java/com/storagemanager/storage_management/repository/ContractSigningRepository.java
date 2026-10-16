package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.ContractSigning;
import com.storagemanager.storage_management.model.enums.SigningKind;
import com.storagemanager.storage_management.model.enums.SigningStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ContractSigningRepository extends JpaRepository<ContractSigning, Long> {

    /** Las firmas de un contrato, la última primero. */
    List<ContractSigning> findByRentalAgreementIdOrderByIdDesc(Long rentalId);

    Optional<ContractSigning> findFirstByRentalAgreementIdAndKindAndStatus(
            Long rentalId, SigningKind kind, SigningStatus status);

    boolean existsByRentalAgreementIdAndStatus(Long rentalId, SigningStatus status);

    boolean existsByRentalAgreementIdAndKindAndStatus(Long rentalId, SigningKind kind, SigningStatus status);

    /**
     * La firma, bloqueada hasta que acabe la transacción. Todo lo que la cambia
     * pasa por aquí: dos firmantes a la vez no pueden colgar su línea del
     * registro de la misma anterior, ni el último firmar dos veces.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ContractSigning s where s.id = :id")
    Optional<ContractSigning> lockById(Long id);

    /** Si un documento es el PDF congelado o el firmado de alguna firma: entonces no se borra. */
    @Query("select count(s) > 0 from ContractSigning s left join s.signedDocument signed "
            + "where s.originalDocument.id = :documentId or signed.id = :documentId")
    boolean usesDocument(Long documentId);
}
