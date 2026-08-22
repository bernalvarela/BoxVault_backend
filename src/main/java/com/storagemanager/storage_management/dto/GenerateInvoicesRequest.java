package com.storagemanager.storage_management.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class GenerateInvoicesRequest {
    @NotNull
    @Min(1)
    @Max(12)
    private Integer month;

    @NotNull
    @Min(2000)
    private Integer year;
}
