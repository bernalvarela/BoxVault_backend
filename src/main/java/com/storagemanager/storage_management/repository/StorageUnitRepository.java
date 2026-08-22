package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.model.enums.UnitStatus;
import com.storagemanager.storage_management.model.enums.UnitType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StorageUnitRepository extends JpaRepository<StorageUnit, Long> {
    Optional<StorageUnit> findByUnitNumber(String unitNumber);
    List<StorageUnit> findByStatus(UnitStatus status);
    List<StorageUnit> findByType(UnitType type);
    long countByStatus(UnitStatus status);
}
