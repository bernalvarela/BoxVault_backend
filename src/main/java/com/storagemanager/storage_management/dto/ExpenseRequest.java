package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.model.enums.ExpenseCategory;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class ExpenseRequest {

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
    private BigDecimal amount;

    @NotBlank(message = "Description is required")
    @Size(max = 255, message = "Description must be at most 255 characters")
    private String description;

    @NotNull(message = "Category is required")
    private ExpenseCategory category;

    @NotNull(message = "Expense date is required")
    private LocalDate expenseDate;

    /** Optional: leave null for a general expense. */
    private Long storageUnitId;

    /**
     * Optional: for a general expense (no storageUnitId), the group it belongs to.
     * Ignored when storageUnitId is given (the unit's group applies).
     */
    private Long storageGroupId;
}
