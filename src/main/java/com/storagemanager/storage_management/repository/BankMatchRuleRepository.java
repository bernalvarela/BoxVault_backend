package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.BankMatchRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Lo aprendido de las revisiones de extractos. */
@Repository
public interface BankMatchRuleRepository extends JpaRepository<BankMatchRule, Long> {

    List<BankMatchRule> findAllByOrderByCreatedAtDescIdDesc();

    /** Las reglas aprendidas que llevan a ese cliente: se van con su ficha. */
    List<BankMatchRule> findByClientId(Long clientId);

    Optional<BankMatchRule> findFirstByPattern(String pattern);
}
