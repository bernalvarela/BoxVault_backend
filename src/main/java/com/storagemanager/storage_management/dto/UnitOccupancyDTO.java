package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.model.enums.UnitKind;
import com.storagemanager.storage_management.model.enums.UnitStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnitOccupancyDTO {
    private Long id;
    private String unitNumber;
    private String name;
    private UnitKind kind;
    private boolean vatApplicable; // false for apartments: base == total, VAT == 0
    private Double sizeSquareMeters;
    private UnitStatus status;
    private String location;
    /** The local (parent unit) this unit sits in, if any. */
    private Long parentUnitId;
    private String parentUnitNumber;
    private String parentUnitName;
    /** Root unit the statistics filter by (the unit itself when it has no parent). */
    private Long rootUnitId;
    private String rootUnitName;
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

    // Gastos imputados directamente a este trastero y resultado neto (ingresos - gastos)
    private BigDecimal totalExpenses;
    private BigDecimal netResult;
}
