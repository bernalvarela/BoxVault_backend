package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.BankImportLine;
import com.storagemanager.storage_management.model.enums.BankLineStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/** Los movimientos de los extractos. */
@Repository
public interface BankImportLineRepository extends JpaRepository<BankImportLine, Long> {

    /**
     * Las huellas que ya están en otro extracto y no se descartaron: un
     * movimiento con una de ellas es un duplicado.
     */
    @Query("SELECT l.fingerprint FROM BankImportLine l WHERE l.fingerprint IN :fingerprints AND l.status <> :discarded")
    Set<String> findLiveFingerprints(@Param("fingerprints") Collection<String> fingerprints,
                                     @Param("discarded") BankLineStatus discarded);

    List<BankImportLine> findByBankImportIdOrderByLineNumberAsc(Long importId);
}
