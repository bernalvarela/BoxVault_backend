package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.Ownership;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface OwnershipRepository extends JpaRepository<Ownership, Long> {
    List<Ownership> findByOwnerId(Long ownerId);
    List<Ownership> findByStorageUnitId(Long storageUnitId);
    List<Ownership> findByStorageGroupId(Long storageGroupId);
    Optional<Ownership> findByOwnerIdAndStorageUnitId(Long ownerId, Long storageUnitId);
    Optional<Ownership> findByOwnerIdAndStorageGroupId(Long ownerId, Long storageGroupId);
    long countByOwnerId(Long ownerId);
    long countByStorageUnitId(Long storageUnitId);
    long countByStorageGroupId(Long storageGroupId);

    @Transactional
    void deleteByStorageUnitId(Long storageUnitId);
}
