package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.BankImport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Los extractos subidos. */
@Repository
public interface BankImportRepository extends JpaRepository<BankImport, Long> {

    List<BankImport> findAllByOrderByCreatedAtDescIdDesc();

    boolean existsByProfileId(Long profileId);
}
