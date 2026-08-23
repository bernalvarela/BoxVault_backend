package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.model.enums.RentalStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Full history of a storage unit: how its price evolved over time and
 * every rental agreement (past and current) with the rent actually agreed.
 */
@Data
@Builder
public class UnitHistoryDTO {

    private Long unitId;
    private String unitNumber;
    private String unitName;
    private BigDecimal currentMonthlyPrice;

    private List<PriceEntry> priceHistory;
    private List<RentalEntry> rentalHistory;

    @Data
    @Builder
    public static class PriceEntry {
        private LocalDate effectiveFrom;
        private BigDecimal monthlyPrice;          // Total con IVA
        private BigDecimal monthlyPriceWithoutVat; // Base sin IVA
        private BigDecimal monthlyPriceVatAmount;  // IVA 21%
        private String notes;
    }

    @Data
    @Builder
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
