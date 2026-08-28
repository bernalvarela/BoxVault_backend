package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.model.enums.UnitKind;
import com.storagemanager.storage_management.model.enums.UnitStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface StorageUnitRepository extends JpaRepository<StorageUnit, Long> {
    Optional<StorageUnit> findByUnitNumber(String unitNumber);
    List<StorageUnit> findByStatus(UnitStatus status);
    long countByStatus(UnitStatus status);
    List<StorageUnit> findByKind(UnitKind kind);
    List<StorageUnit> findByKindAndStatus(UnitKind kind, UnitStatus status);
    List<StorageUnit> findByKindIsNull();

    List<StorageUnit> findByStorageGroupIdIn(Collection<Long> groupIds);
    List<StorageUnit> findByStorageGroupIsNull();
    long countByStorageGroupId(Long groupId);
    long countByStorageGroupIdAndStatus(Long groupId, UnitStatus status);
}
