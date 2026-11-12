package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.UnitDocumentEntryExpense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface UnitDocumentEntryExpenseRepository extends JpaRepository<UnitDocumentEntryExpense, Long> {

    /** Los gastos de varias entradas a la vez, del más reciente al más antiguo. */
    List<UnitDocumentEntryExpense> findByEntryIdInOrderByExpenseExpenseDateDescExpenseIdDesc(Collection<Long> entryIds);

    List<UnitDocumentEntryExpense> findByEntryId(Long entryId);

    Optional<UnitDocumentEntryExpense> findByExpenseId(Long expenseId);

    /** Los gastos de una unidad que ya van en alguna entrada. */
    List<UnitDocumentEntryExpense> findByEntryStorageUnitId(Long storageUnitId);
}
