package com.storagemanager.storage_management.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MonthlyRevenueDTO {
    private String monthLabel; // e.g. "Ene 2026", "Feb 2026"
    private int year;
    private int month;

    // Totales con IVA
    private BigDecimal expectedRevenue;
    private BigDecimal collectedRevenue;
    private BigDecimal pendingRevenue;

    // Desglose sin IVA
    private BigDecimal expectedRevenueWithoutVat;
    private BigDecimal collectedRevenueWithoutVat;
    private BigDecimal pendingRevenueWithoutVat;

    // Cuotas de IVA 21%
    private BigDecimal expectedVatAmount;
    private BigDecimal collectedVatAmount;
    private BigDecimal pendingVatAmount;

    private long paidCount;
    private long pendingCount;
    private long overdueCount;

    // Gastos del periodo y resultado neto (cobrado - gastos)
    private BigDecimal expenses;
    private long expenseCount;
    private BigDecimal netResult;
}
