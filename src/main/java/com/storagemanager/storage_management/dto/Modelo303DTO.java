package com.storagemanager.storage_management.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Modelo 303 (quarterly VAT return): the VAT-bearing rent (storage units) of a
 * year, quarter by quarter, split into taxable base and 21 % VAT quota.
 * Figures follow the billing period of each mensualidad: "collected" is what was
 * actually paid (PAID payments), "expected" every mensualidad of the quarter and
 * "pending" the PENDING / OVERDUE ones. When an owner (normally the comunidad de
 * bienes) is given, only the units they hold a share of are included.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Modelo303DTO {

    private int year;
    /** Owner (entity) the return is scoped to; null = every VAT-bearing unit. */
    private Long ownerId;
    private String ownerName;
    private List<String> unitNumbers;
    private long unitCount;

    private List<Quarter> quarters;

    // Year totals
    private BigDecimal collectedBase;
    private BigDecimal collectedVat;
    private BigDecimal collectedTotal;
    private BigDecimal expectedBase;
    private BigDecimal expectedVat;
    private BigDecimal expectedTotal;
    private BigDecimal pendingBase;
    private BigDecimal pendingVat;
    private BigDecimal pendingTotal;
    private long paidCount;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Quarter {
        private int quarter;
        /** e.g. "1T 2026". */
        private String label;
        private int startMonth;
        private int endMonth;

        private BigDecimal collectedBase;
        private BigDecimal collectedVat;
        private BigDecimal collectedTotal;
        private BigDecimal expectedBase;
        private BigDecimal expectedVat;
        private BigDecimal expectedTotal;
        private BigDecimal pendingBase;
        private BigDecimal pendingVat;
        private BigDecimal pendingTotal;

        private long paidCount;
        private long pendingCount;
        private long overdueCount;

        /** The mensualidades of the quarter the figures come from (PAID ones make the "collected" amounts). */
        private List<PaymentLine> payments;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentLine {
        private Long paymentId;
        private Long unitId;
        private String unitNumber;
        private String unitName;
        private String clientName;
        private int billingPeriodYear;
        private int billingPeriodMonth;
        private LocalDate paymentDate;
        private String status;
        /** Gross amount (paid for PAID payments, due otherwise). */
        private BigDecimal total;
        private BigDecimal base;
        private BigDecimal vat;
    }
}
