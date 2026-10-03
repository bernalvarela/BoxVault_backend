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
     * Las huellas que ya están en otro extracto y no se descartaron a mano: un
     * movimiento con una de ellas es un duplicado. Las que se dieron por "ya
     * registradas" cuentan aunque queden descartadas: ese movimiento ya está
     * casado con su cobro o su gasto.
     */
    @Query("SELECT l.fingerprint FROM BankImportLine l WHERE l.fingerprint IN :fingerprints "
            + "AND (l.status <> :discarded OR l.alreadyRecorded = true)")
    Set<String> findLiveFingerprints(@Param("fingerprints") Collection<String> fingerprints,
                                     @Param("discarded") BankLineStatus discarded);

    List<BankImportLine> findByBankImportIdOrderByLineNumberAsc(Long importId);

    /**
     * Los cobros que ya están casados con un movimiento de otro extracto, porque
     * se crearon al aplicarlo o porque se dio por "ya registrado": no pueden ser
     * también el de un movimiento nuevo.
     */
    @Query("SELECT DISTINCT l.paymentId FROM BankImportLine l WHERE l.paymentId IS NOT NULL")
    Set<Long> findLinkedPaymentIds();
}
