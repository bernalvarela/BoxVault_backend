package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.ExpenseDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExpenseDocumentRepository extends JpaRepository<ExpenseDocument, Long> {

    /** Los documentos de un gasto, el último subido primero. */
    List<ExpenseDocument> findByExpenseIdOrderByDocumentUploadedAtDescDocumentIdDesc(Long expenseId);

    /** La relación con ese documento, sólo si es de ese gasto: la url lleva los dos. */
    Optional<ExpenseDocument> findByExpenseIdAndDocumentId(Long expenseId, Long documentId);

    /** Los documentos de los gastos de una unidad, del gasto más reciente al más antiguo. */
    List<ExpenseDocument> findByExpenseStorageUnitIdOrderByExpenseExpenseDateDescExpenseIdDescDocumentIdAsc(Long storageUnitId);

    /** Cuántos documentos tiene cada gasto que tenga alguno: filas [expenseId, count]. */
    @Query("SELECT d.expense.id, COUNT(d) FROM ExpenseDocument d GROUP BY d.expense.id")
    List<Object[]> countByExpense();
}
