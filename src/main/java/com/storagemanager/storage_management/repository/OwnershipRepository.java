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
    Optional<Ownership> findByOwnerIdAndStorageUnitId(Long ownerId, Long storageUnitId);
    long countByOwnerId(Long ownerId);
    long countByStorageUnitId(Long storageUnitId);

    @Transactional
    void deleteByStorageUnitId(Long storageUnitId);
}
