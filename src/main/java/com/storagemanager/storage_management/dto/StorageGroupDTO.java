package com.storagemanager.storage_management.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A storage group plus how many units it currently contains. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StorageGroupDTO {
    private Long id;
    private String name;
    private String description;
    private long unitCount;
    private long occupiedUnits;
}
