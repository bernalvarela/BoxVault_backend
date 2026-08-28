package com.storagemanager.storage_management.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * One share of an owner in a group or a unit. Exactly one of the storageUnit* /
 * storageGroup* pairs is filled. {@code inherited} is only meaningful when the
 * share is returned as part of a unit's effective owners: true means it comes
 * from the unit's group because the unit has no shares of its own.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OwnershipDTO {
    private Long id;

    private Long ownerId;
    private String ownerName;

    private Long storageUnitId;
    private String storageUnitNumber;
    private String storageUnitName;

    private Long storageGroupId;
    private String storageGroupName;

    private BigDecimal sharePercent;
    private String notes;

    private boolean inherited;
}
