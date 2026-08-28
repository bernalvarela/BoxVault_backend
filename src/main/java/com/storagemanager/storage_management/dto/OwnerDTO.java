package com.storagemanager.storage_management.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/** An owner together with every share they hold (in groups and in units). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OwnerDTO {
    private Long id;
    private String fullName;
    private String documentId;
    private String email;
    private String phone;
    private String bankAccount;
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private List<OwnershipDTO> ownerships;
}
