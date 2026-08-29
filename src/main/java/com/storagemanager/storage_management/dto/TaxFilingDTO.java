package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.model.enums.TaxModel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** A filed tax return; {@code snapshot} is the JSON of the report at filing time. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaxFilingDTO {
    private Long id;
    private TaxModel model;
    private Integer year;
    private Integer quarter;
    private Long ownerId;
    private String ownerName;
    private LocalDate filedDate;
    private BigDecimal amount;
    private String description;
    private String snapshot;
    private String notes;
    private LocalDateTime createdAt;
}
