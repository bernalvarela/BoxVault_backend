package com.storagemanager.storage_management.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Creates or updates a share. Give either {@code storageUnitId} (unit-level
 * share) or {@code storageGroupId} (group-level share), never both.
 */
@Data
public class OwnershipRequest {

    @NotNull(message = "Owner is required")
    private Long ownerId;

    private Long storageUnitId;

    private Long storageGroupId;

    @NotNull(message = "Share is required")
    @DecimalMin(value = "0.0001", message = "Share must be greater than zero")
    @DecimalMax(value = "100", message = "Share cannot exceed 100 %")
    @Digits(integer = 3, fraction = 4, message = "Share admits at most four decimals")
    private BigDecimal sharePercent;

    @Size(max = 255, message = "Notes must be at most 255 characters")
    private String notes;
}
