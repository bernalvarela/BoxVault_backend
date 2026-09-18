package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Las facturas emitidas. Aquí no hay borrado: una factura se corrige con una
 * rectificativa, nunca se quita de la serie.
 */
@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    /** Las de una mensualidad, la más reciente primero: la ordinaria y sus rectificativas. */
    List<Invoice> findByPaymentIdOrderByIssuedOnDescIdDesc(Long paymentId);

    /**
     * El último número emitido con ese prefijo ({@code A2026/}, {@code R2026/}),
     * para seguir la serie. Con el ordinal a cuatro cifras y ceros delante, el
     * orden por texto es el orden por número.
     */
    @Query("SELECT MAX(i.number) FROM Invoice i WHERE i.number LIKE CONCAT(:prefix, '%')")
    Optional<String> lastNumber(@Param("prefix") String prefix);
}
