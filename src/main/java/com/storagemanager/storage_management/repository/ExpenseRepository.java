package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.Expense;
import com.storagemanager.storage_management.model.enums.ExpenseCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface ExpenseRepository extends JpaRepository<Expense, Long> {

    List<Expense> findAllByOrderByExpenseDateDesc();
    List<Expense> findByStorageUnitId(Long storageUnitId);
    List<Expense> findByExpenseDateBetween(LocalDate startDate, LocalDate endDate);
    List<Expense> findByCategory(ExpenseCategory category);
    List<Expense> findByStorageUnitIsNullAndStorageGroupIsNull();
    long countByStorageGroupId(Long storageGroupId);

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM Expense e")
    BigDecimal sumTotalExpenses();

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM Expense e WHERE e.expenseDate >= :startDate AND e.expenseDate <= :endDate")
    BigDecimal sumExpensesBetween(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Query("SELECT COUNT(e) FROM Expense e WHERE e.expenseDate >= :startDate AND e.expenseDate <= :endDate")
    long countExpensesBetween(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM Expense e WHERE e.storageUnit.id = :unitId")
    BigDecimal sumExpensesForUnit(@Param("unitId") Long unitId);

    @Query("SELECT e.storageUnit.id, COALESCE(SUM(e.amount), 0) FROM Expense e " +
           "WHERE e.storageUnit IS NOT NULL GROUP BY e.storageUnit.id")
    List<Object[]> sumExpensesByStorageUnit();

    @Query("SELECT e.category, COALESCE(SUM(e.amount), 0), COUNT(e) FROM Expense e GROUP BY e.category")
    List<Object[]> sumExpensesByCategory();

    @Query("SELECT e.category, COALESCE(SUM(e.amount), 0), COUNT(e) FROM Expense e " +
           "WHERE e.expenseDate >= :startDate AND e.expenseDate <= :endDate GROUP BY e.category")
    List<Object[]> sumExpensesByCategoryBetween(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);
}
