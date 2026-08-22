package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.enums.RentalStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RentalAgreementRepository extends JpaRepository<RentalAgreement, Long> {
    Optional<RentalAgreement> findByAgreementNumber(String agreementNumber);
    List<RentalAgreement> findByStatus(RentalStatus status);
    List<RentalAgreement> findByClientId(Long clientId);
    List<RentalAgreement> findByStorageUnitId(Long storageUnitId);
    Optional<RentalAgreement> findByStorageUnitIdAndStatus(Long storageUnitId, RentalStatus status);
    long countByStatus(RentalStatus status);

    @Query("SELECT r FROM RentalAgreement r WHERE r.status = 'ACTIVE'")
    List<RentalAgreement> findAllActiveRentals();
}
