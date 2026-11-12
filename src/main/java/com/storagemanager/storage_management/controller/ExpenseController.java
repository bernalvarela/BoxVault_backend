package com.storagemanager.storage_management.controller;

import com.storagemanager.storage_management.dto.ExpenseDocumentDTO;
import com.storagemanager.storage_management.dto.ExpenseRequest;
import com.storagemanager.storage_management.model.Expense;
import com.storagemanager.storage_management.model.enums.DocumentType;
import com.storagemanager.storage_management.model.enums.ExpenseCategory;
import com.storagemanager.storage_management.service.ExpenseDocumentService;
import com.storagemanager.storage_management.service.ExpenseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/expenses")
@RequiredArgsConstructor
public class ExpenseController {

    private final ExpenseService expenseService;
    private final ExpenseDocumentService expenseDocumentService;

    @PreAuthorize("@access.can('GASTOS','LEER')")
    @GetMapping
    public ResponseEntity<List<Expense>> getExpenses(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Long storageUnitId,
            @RequestParam(required = false) Long rootId,
            @RequestParam(required = false) ExpenseCategory category,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false, defaultValue = "desc") String direction) {
        ExpenseService.Filter filter = new ExpenseService.Filter(
                year, month, storageUnitId, rootId, category, startDate, endDate, minAmount, maxAmount);
        boolean ascending = "asc".equalsIgnoreCase(direction);
        return ResponseEntity.ok(expenseService.searchExpenses(filter, ExpenseService.SortBy.from(sortBy), ascending));
    }

    @PreAuthorize("@access.can('GASTOS','LEER')")
    @GetMapping("/categories")
    public ResponseEntity<ExpenseCategory[]> getCategories() {
        return ResponseEntity.ok(ExpenseCategory.values());
    }

    @PreAuthorize("@access.can('GASTOS','LEER')")
    @GetMapping("/{id}")
    public ResponseEntity<Expense> getExpenseById(@PathVariable Long id) {
        return ResponseEntity.ok(expenseService.getExpenseById(id));
    }

    @PreAuthorize("@access.can('GASTOS','ESCRIBIR')")
    @PostMapping
    public ResponseEntity<Expense> createExpense(@Valid @RequestBody ExpenseRequest request) {
        return new ResponseEntity<>(expenseService.createExpense(request), HttpStatus.CREATED);
    }

    @PreAuthorize("@access.can('GASTOS','ESCRIBIR')")
    @PutMapping("/{id}")
    public ResponseEntity<Expense> updateExpense(@PathVariable Long id, @Valid @RequestBody ExpenseRequest request) {
        return ResponseEntity.ok(expenseService.updateExpense(id, request));
    }

    @PreAuthorize("@access.can('GASTOS','ADMINISTRAR')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteExpense(@PathVariable Long id) {
        expenseService.deleteExpense(id);
        return ResponseEntity.noContent().build();
    }

    // ---- Documentos: el justificante del pago, la factura...

    @PreAuthorize("@access.can('GASTOS','LEER')")
    @GetMapping("/document-types")
    public ResponseEntity<List<DocumentType>> getDocumentTypes() {
        return ResponseEntity.ok(DocumentType.forExpense());
    }

    /** Cuántos documentos tiene cada gasto que tenga alguno, para marcarlo en el listado. */
    @PreAuthorize("@access.can('GASTOS','LEER')")
    @GetMapping("/document-counts")
    public ResponseEntity<Map<Long, Long>> getDocumentCounts() {
        return ResponseEntity.ok(expenseDocumentService.countByExpense());
    }

    @PreAuthorize("@access.can('GASTOS','LEER')")
    @GetMapping("/{id}/documents")
    public ResponseEntity<List<ExpenseDocumentDTO>> getDocuments(@PathVariable Long id) {
        return ResponseEntity.ok(expenseDocumentService.getDocuments(id));
    }

    @PreAuthorize("@access.can('GASTOS','ESCRIBIR')")
    @PostMapping(value = "/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ExpenseDocumentDTO> uploadDocument(
            @PathVariable Long id,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) DocumentType documentType,
            @RequestParam(required = false) String description) {
        return new ResponseEntity<>(expenseDocumentService.upload(id, file, documentType, description), HttpStatus.CREATED);
    }

    @PreAuthorize("@access.can('GASTOS','LEER')")
    @GetMapping("/{id}/documents/{documentId}/download")
    public ResponseEntity<Resource> downloadDocument(@PathVariable Long id, @PathVariable Long documentId) {
        return DocumentDownload.respond(expenseDocumentService.download(id, documentId));
    }

    @PreAuthorize("@access.can('GASTOS','ADMINISTRAR')")
    @DeleteMapping("/{id}/documents/{documentId}")
    public ResponseEntity<Void> deleteDocument(@PathVariable Long id, @PathVariable Long documentId) {
        expenseDocumentService.delete(id, documentId);
        return ResponseEntity.noContent().build();
    }
}
