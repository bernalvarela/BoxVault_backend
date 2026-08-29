package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.model.enums.OwnerType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * One share of an owner in a unit. When returned as part of a unit's effective
 * owners, {@code inherited} is true if the share actually belongs to an ancestor
 * unit ({@code inheritedFromUnitNumber}) because the unit has no shares of its own.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OwnershipDTO {
    private Long id;

    private Long ownerId;
    private String ownerName;
    private OwnerType ownerType;

    private Long storageUnitId;
    private String storageUnitNumber;
    private String storageUnitName;
    private Long parentUnitId;
    private String parentUnitNumber;
    private String parentUnitName;

    private BigDecimal sharePercent;
    private String notes;

    private boolean inherited;
    private Long inheritedFromUnitId;
    private String inheritedFromUnitNumber;
}
