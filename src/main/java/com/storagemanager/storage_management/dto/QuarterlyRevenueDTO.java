package com.storagemanager.storage_management.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * DTO for quarterly revenue statistics
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuarterlyRevenueDTO {
    private String quarterLabel; // e.g. "Q1 2026", "Q2 2026"
    private int year;
    private int quarter; // 1-4

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
}