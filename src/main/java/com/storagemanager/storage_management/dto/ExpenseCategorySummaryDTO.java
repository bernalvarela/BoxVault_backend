package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.model.enums.ExpenseCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Total spent in one category over a period. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExpenseCategorySummaryDTO {
    private ExpenseCategory category;
    private BigDecimal amount;
    private long count;
}
