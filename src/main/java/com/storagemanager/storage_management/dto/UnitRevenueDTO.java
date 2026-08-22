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
public class UnitRevenueDTO {
    private Long unitId;
    private String unitNumber;
    private String unitName;
    private BigDecimal totalRevenue;
}
