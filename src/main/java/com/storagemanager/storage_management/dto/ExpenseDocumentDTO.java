package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.model.Document;
import com.storagemanager.storage_management.model.Expense;
import com.storagemanager.storage_management.model.ExpenseDocument;
import com.storagemanager.storage_management.model.enums.DocumentType;
import com.storagemanager.storage_management.model.enums.ExpenseCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Un documento archivado en un gasto, aplanado igual que
 * {@link TaxNoticeDocumentDTO}: el {@code id} es el del documento.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExpenseDocumentDTO {

    private Long id;
    private Long expenseId;
    private DocumentType documentType;
    private String fileName;
    private String contentType;
    private Long sizeBytes;
    private String description;
    private LocalDateTime uploadedAt;
    /** Ruta de descarga, relativa: el frontend la usa tal cual. */
    private String downloadUrl;

    /** Del gasto, para enseñarlo junto al documento fuera de la pantalla de gastos (en la unidad). */
    private LocalDate expenseDate;
    private String expenseDescription;
    private BigDecimal expenseAmount;
    private ExpenseCategory expenseCategory;

    public static ExpenseDocumentDTO of(ExpenseDocument link) {
        Expense expense = link.getExpense();
        Long expenseId = expense.getId();
        Document doc = link.getDocument();
        return ExpenseDocumentDTO.builder()
                .id(doc.getId())
                .expenseId(expenseId)
                .expenseDate(expense.getExpenseDate())
                .expenseDescription(expense.getDescription())
                .expenseAmount(expense.getAmount())
                .expenseCategory(expense.getCategory())
                .documentType(doc.getDocumentType())
                .fileName(doc.getFileName())
                .contentType(doc.getContentType())
                .sizeBytes(doc.getSizeBytes())
                .description(doc.getDescription())
                .uploadedAt(doc.getUploadedAt())
                .downloadUrl("/api/expenses/" + expenseId + "/documents/" + doc.getId() + "/download")
                .build();
    }
}
