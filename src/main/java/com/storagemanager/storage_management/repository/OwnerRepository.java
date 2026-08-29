package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.Owner;
import com.storagemanager.storage_management.model.enums.OwnerType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OwnerRepository extends JpaRepository<Owner, Long> {
    Optional<Owner> findByFullNameIgnoreCase(String fullName);
    List<Owner> findAllByOrderByFullNameAsc();
    List<Owner> findByTypeOrderByFullNameAsc(OwnerType type);
}
