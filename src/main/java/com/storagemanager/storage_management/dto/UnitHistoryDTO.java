package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.model.enums.RentalStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Full history of a storage unit: how its price evolved over time and
 * every rental agreement (past and current) with the rent actually agreed.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnitHistoryDTO {

    private Long unitId;
    private String unitNumber;
    private String unitName;
    private BigDecimal currentMonthlyPrice;

    // Ingresos totales cobrados de este trastero
    private BigDecimal totalRevenue;           // Bruto (total con IVA)
    private BigDecimal totalRevenueWithoutVat; // Neto (base sin IVA)
    private BigDecimal totalRevenueVatAmount;  // IVA 21%

    // Gastos imputados a este trastero y resultado neto (bruto cobrado - gastos)
    private BigDecimal totalExpenses;
    private BigDecimal netResult;

    private List<PriceEntry> priceHistory;
    private List<RentalEntry> rentalHistory;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PriceEntry {
        private LocalDate effectiveFrom;
        private BigDecimal monthlyPrice;          // Total con IVA
        private BigDecimal monthlyPriceWithoutVat; // Base sin IVA
        private BigDecimal monthlyPriceVatAmount;  // IVA 21%
        private String notes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RentalEntry {
        private Long rentalId;
        private String agreementNumber;
        private String clientName;
        private LocalDate startDate;
        private LocalDate endDate;
        private BigDecimal monthlyRent;
        private RentalStatus status;
    }
}
