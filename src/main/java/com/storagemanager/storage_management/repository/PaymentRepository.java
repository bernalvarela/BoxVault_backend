package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.Payment;
import com.storagemanager.storage_management.model.enums.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {
    List<Payment> findByStatus(PaymentStatus status);
    List<Payment> findByRentalAgreementId(Long rentalAgreementId);
    List<Payment> findByClientId(Long clientId);
    List<Payment> findByStorageUnitId(Long storageUnitId);
    List<Payment> findByBillingPeriodYearAndBillingPeriodMonth(Integer year, Integer month);
    List<Payment> findByBillingPeriodYearAndBillingPeriodMonthBetween(int startYear, int startMonth, int endYear, int endMonth);
    List<Payment> findByBillingPeriodYear(int year);
    List<Payment> findByDueDateBetween(LocalDate startDate, LocalDate endDate);

    Optional<Payment> findByRentalAgreementIdAndBillingPeriodYearAndBillingPeriodMonth(
            Long rentalAgreementId, Integer year, Integer month);

    @Query("SELECT p FROM Payment p WHERE p.dueDate < :currentDate AND p.status = 'PENDING'")
    List<Payment> findOverduePayments(@Param("currentDate") LocalDate currentDate);

    @Query("SELECT COALESCE(SUM(p.amountPaid), 0) FROM Payment p WHERE p.status = 'PAID'")
    BigDecimal sumTotalPaidRevenue();

    @Query("SELECT COALESCE(SUM(p.amountPaid), 0) FROM Payment p WHERE p.status = 'PAID' AND p.billingPeriodYear = :year AND p.billingPeriodMonth = :month")
    BigDecimal sumRevenueForMonth(@Param("year") Integer year, @Param("month") Integer month);

    @Query("SELECT COALESCE(SUM(p.amountDue), 0) FROM Payment p WHERE (p.status = 'PENDING' OR p.status = 'OVERDUE') AND p.billingPeriodYear = :year AND p.billingPeriodMonth = :month")
    BigDecimal sumPendingRevenueForMonth(@Param("year") Integer year, @Param("month") Integer month);

    @Query("SELECT COALESCE(SUM(p.amountDue), 0) FROM Payment p WHERE p.status = 'OVERDUE' OR (p.status = 'PENDING' AND p.dueDate < :currentDate)")
    BigDecimal sumTotalOverdueAmount(@Param("currentDate") LocalDate currentDate);

    @Query("SELECT p.storageUnit.id, p.storageUnit.unitNumber, COALESCE(SUM(p.amountPaid), 0) " +
           "FROM Payment p WHERE p.status = 'PAID' " +
           "GROUP BY p.storageUnit.id, p.storageUnit.unitNumber")
    List<Object[]> sumRevenueByStorageUnit();

    // Quarterly aggregation methods
    @Query("SELECT COALESCE(SUM(p.amountPaid), 0) FROM Payment p WHERE p.status = 'PAID' AND p.billingPeriodYear = :year AND p.billingPeriodMonth >= :startMonth AND p.billingPeriodMonth <= :endMonth")
    BigDecimal sumRevenueForQuarter(@Param("year") Integer year, @Param("startMonth") Integer startMonth, @Param("endMonth") Integer endMonth);

    @Query("SELECT COALESCE(SUM(p.amountDue), 0) FROM Payment p WHERE (p.status = 'PENDING' OR p.status = 'OVERDUE') AND p.billingPeriodYear = :year AND p.billingPeriodMonth >= :startMonth AND p.billingPeriodMonth <= :endMonth")
    BigDecimal sumPendingRevenueForQuarter(@Param("year") Integer year, @Param("startMonth") Integer startMonth, @Param("endMonth") Integer endMonth);

    // Annual aggregation methods
    @Query("SELECT COALESCE(SUM(p.amountPaid), 0) FROM Payment p WHERE p.status = 'PAID' AND p.billingPeriodYear = :year")
    BigDecimal sumRevenueForYear(@Param("year") Integer year);

    @Query("SELECT COALESCE(SUM(p.amountDue), 0) FROM Payment p WHERE (p.status = 'PENDING' OR p.status = 'OVERDUE') AND p.billingPeriodYear = :year")
    BigDecimal sumPendingRevenueForYear(@Param("year") Integer year);
}
