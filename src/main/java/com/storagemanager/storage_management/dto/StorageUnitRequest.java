package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.model.enums.UnitKind;
import com.storagemanager.storage_management.model.enums.UnitStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class StorageUnitRequest {
    @NotBlank(message = "Unit number is required")
    private String unitNumber;

    @NotBlank(message = "Unit name is required")
    private String name;

    @NotNull(message = "Storage group is required")
    private Long storageGroupId;

    /** STORAGE_UNIT (default) or APARTMENT (VAT exempt). */
    private UnitKind kind;

    @NotNull(message = "Size is required")
    @DecimalMin(value = "0.1", message = "Size must be greater than 0")
    private Double sizeSquareMeters;

    private String dimensions;

    private String location;

    @NotNull(message = "Monthly rate is required")
    @DecimalMin(value = "0.0", message = "Monthly rate cannot be negative")
    private BigDecimal baseMonthlyRate;

    private UnitStatus status;

    private String description;
}
