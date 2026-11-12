package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.model.Document;
import com.storagemanager.storage_management.model.Expense;
import com.storagemanager.storage_management.model.UnitDocumentEntry;
import com.storagemanager.storage_management.model.UnitDocumentEntryDocument;
import com.storagemanager.storage_management.model.enums.DocumentType;
import com.storagemanager.storage_management.model.enums.ExpenseCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Una entrada de documentos de una unidad, con sus ficheros. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnitDocumentEntryDTO {

    private Long id;
    private Long storageUnitId;
    private String title;
    private LocalDate entryDate;
    private String notes;
    private LocalDateTime createdAt;
    private String createdBy;
    private List<FileDTO> documents;
    /**
     * Los gastos relacionados con la entrada, con sus justificantes. Sólo viene
     * en el listado y para quien puede ver los gastos; si no, es null.
     */
    private List<LinkedExpenseDTO> expenses;

    /** Un gasto relacionado con la entrada: lo que se enseña de él y sus documentos. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LinkedExpenseDTO {
        private Long id;
        private String description;
        private LocalDate expenseDate;
        private BigDecimal amount;
        private ExpenseCategory category;
        private List<ExpenseDocumentDTO> documents;

        public static LinkedExpenseDTO of(Expense expense, List<ExpenseDocumentDTO> documents) {
            return LinkedExpenseDTO.builder()
                    .id(expense.getId())
                    .description(expense.getDescription())
                    .expenseDate(expense.getExpenseDate())
                    .amount(expense.getAmount())
                    .category(expense.getCategory())
                    .documents(documents)
                    .build();
        }
    }

    public static UnitDocumentEntryDTO of(UnitDocumentEntry entry, List<UnitDocumentEntryDocument> links) {
        Long unitId = entry.getStorageUnit().getId();
        return UnitDocumentEntryDTO.builder()
                .id(entry.getId())
                .storageUnitId(unitId)
                .title(entry.getTitle())
                .entryDate(entry.getEntryDate())
                .notes(entry.getNotes())
                .createdAt(entry.getCreatedAt())
                .createdBy(entry.getCreatedBy())
                .documents(links.stream().map(link -> FileDTO.of(unitId, entry.getId(), link.getDocument())).toList())
                .build();
    }

    /** Un fichero de la entrada; el {@code id} es el del documento. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FileDTO {
        private Long id;
        private DocumentType documentType;
        private String fileName;
        private String contentType;
        private Long sizeBytes;
        private String description;
        private LocalDateTime uploadedAt;
        /** Ruta de descarga, relativa: el frontend la usa tal cual. */
        private String downloadUrl;

        static FileDTO of(Long unitId, Long entryId, Document doc) {
            return FileDTO.builder()
                    .id(doc.getId())
                    .documentType(doc.getDocumentType())
                    .fileName(doc.getFileName())
                    .contentType(doc.getContentType())
                    .sizeBytes(doc.getSizeBytes())
                    .description(doc.getDescription())
                    .uploadedAt(doc.getUploadedAt())
                    .downloadUrl("/api/storages/" + unitId + "/document-entries/" + entryId
                            + "/documents/" + doc.getId() + "/download")
                    .build();
        }
    }

    /** Lo que se manda al cambiar un fichero ya subido: su tipo y su descripción. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FileUpdate {
        private DocumentType documentType;
        @Size(max = 255)
        private String description;
    }

    /** Lo que se manda al crear o cambiar una entrada; los ficheros se suben aparte, uno a uno. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Request {
        @NotBlank
        @Size(max = 150)
        private String title;
        @NotNull
        private LocalDate entryDate;
        @Size(max = 1000)
        private String notes;
    }
}
