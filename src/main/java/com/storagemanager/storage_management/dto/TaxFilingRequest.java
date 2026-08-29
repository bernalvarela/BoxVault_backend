package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.model.enums.TaxModel;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class TaxFilingRequest {

    @NotNull(message = "Tax model is required")
    private TaxModel model;

    @NotNull(message = "Year is required")
    @Min(value = 2000, message = "Year must be 2000 or later")
    @Max(value = 2100, message = "Year must be 2100 or earlier")
    private Integer year;

    /** Required for the Modelo 303 (1-4), ignored otherwise. */
    @Min(value = 1, message = "Quarter must be between 1 and 4")
    @Max(value = 4, message = "Quarter must be between 1 and 4")
    private Integer quarter;

    /** The owner filing the return: the person for the IRPF, the comunidad de bienes for the Modelo 184; ignored for the 303. */
    private Long ownerId;

    @NotNull(message = "Filing date is required")
    private LocalDate filedDate;

    private BigDecimal amount;

    @Size(max = 255, message = "Description must be at most 255 characters")
    private String description;

    /** JSON of the report as shown when filing. */
    private String snapshot;

    private String notes;
}
