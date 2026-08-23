package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.model.enums.UnitStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class UnitOccupancyDTO {
    private Long id;
    private String unitNumber;
    private String name;
    private Double sizeSquareMeters;
    private UnitStatus status;
    private String location;
    private String currentClientName;
    private String currentAgreementNumber;

    // Financials with VAT breakdown
    private BigDecimal baseMonthlyRate; // Total con IVA
    private BigDecimal baseMonthlyRateWithoutVat; // Base sin IVA
    private BigDecimal baseMonthlyRateVatAmount; // IVA 21%

    private BigDecimal actualMonthlyRent; // Total con IVA
    private BigDecimal actualMonthlyRentWithoutVat; // Base sin IVA
    private BigDecimal actualMonthlyRentVatAmount; // IVA 21%

    private BigDecimal totalRevenueGenerated; // Total con IVA
    private BigDecimal totalRevenueGeneratedWithoutVat; // Base sin IVA
    private BigDecimal totalRevenueGeneratedVatAmount; // IVA 21%
}
