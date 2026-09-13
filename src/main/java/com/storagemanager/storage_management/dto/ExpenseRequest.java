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

    /**
     * Cuota de IVA soportado incluida en el importe, la que se deduce en el 303.
     * Opcional: nula o cero para los gastos sin IVA (el IBI) o cuando no se sabe.
     */
    @DecimalMin(value = "0.00", message = "VAT amount cannot be negative")
    private BigDecimal vatAmount;

    @NotBlank(message = "Description is required")
    @Size(max = 255, message = "Description must be at most 255 characters")
    private String description;

    @NotNull(message = "Category is required")
    private ExpenseCategory category;

    @NotNull(message = "Expense date is required")
    private LocalDate expenseDate;

    /** Optional: the unit (trastero, flat or local) the cost belongs to; leave null for a general expense. */
    private Long storageUnitId;
}
