package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.Payment;
import com.storagemanager.storage_management.model.enums.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Los cobros recibidos. No hay recibos por adelantado, así que aquí no se
 * consulta nada "pendiente": lo que queda por cobrar lo calcula BillingService
 * a partir de los contratos.
 */
@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {
    List<Payment> findByStatus(PaymentStatus status);
    List<Payment> findByRentalAgreementId(Long rentalAgreementId);

    /** Los cobros de varios contratos: los de una persona salen de sus contratos. */
    List<Payment> findByRentalAgreementIdIn(java.util.Collection<Long> rentalAgreementIds);
    List<Payment> findByClientId(Long clientId);
    List<Payment> findByStorageUnitId(Long storageUnitId);
    List<Payment> findByBillingPeriodYearAndBillingPeriodMonth(Integer year, Integer month);
    List<Payment> findByBillingPeriodYear(int year);

    Optional<Payment> findByRentalAgreementIdAndBillingPeriodYearAndBillingPeriodMonth(
            Long rentalAgreementId, Integer year, Integer month);

    @Query("SELECT p.storageUnit.id, p.storageUnit.unitNumber, COALESCE(SUM(p.amountPaid), 0) " +
           "FROM Payment p GROUP BY p.storageUnit.id, p.storageUnit.unitNumber")
    List<Object[]> sumRevenueByStorageUnit();

    @Query("SELECT COALESCE(SUM(p.amountPaid), 0) FROM Payment p WHERE p.storageUnit.id = :unitId")
    BigDecimal sumPaidRevenueForUnit(@Param("unitId") Long unitId);
}
