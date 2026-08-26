package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.StorageGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StorageGroupRepository extends JpaRepository<StorageGroup, Long> {
    Optional<StorageGroup> findByNameIgnoreCase(String name);
    List<StorageGroup> findAllByOrderByNameAsc();
}
