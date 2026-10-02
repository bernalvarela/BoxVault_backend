package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.BankImportProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Cómo es el extracto de cada cuenta. */
@Repository
public interface BankImportProfileRepository extends JpaRepository<BankImportProfile, Long> {

    List<BankImportProfile> findAllByOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);
}
