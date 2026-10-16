package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.ContractSigner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ContractSignerRepository extends JpaRepository<ContractSigner, Long> {

    /** Quien firma con este enlace, por la huella del token. */
    Optional<ContractSigner> findByTokenHash(String tokenHash);
}
