package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.ContractTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Las plantillas de contrato. */
@Repository
public interface ContractTemplateRepository extends JpaRepository<ContractTemplate, Long> {

    List<ContractTemplate> findAllByOrderByNameAsc();

    /** La que se usa cuando el contrato no dice cuál. */
    Optional<ContractTemplate> findFirstByDefaultTemplateIsTrue();

    boolean existsByNameIgnoreCase(String name);
}
