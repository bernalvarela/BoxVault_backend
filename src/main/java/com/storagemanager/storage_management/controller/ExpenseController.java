package com.storagemanager.storage_management.controller;

import com.storagemanager.storage_management.dto.ExpenseRequest;
import com.storagemanager.storage_management.model.Expense;
import com.storagemanager.storage_management.model.enums.ExpenseCategory;
import com.storagemanager.storage_management.service.ExpenseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/expenses")
@RequiredArgsConstructor
public class ExpenseController {

    private final ExpenseService expenseService;

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
}
