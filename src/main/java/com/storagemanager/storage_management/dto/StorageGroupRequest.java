package com.storagemanager.storage_management.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class StorageGroupRequest {
    @NotBlank(message = "Group name is required")
    @Size(max = 150, message = "Group name must be at most 150 characters")
    private String name;

    private String description;
}
