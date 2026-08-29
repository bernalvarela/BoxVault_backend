package com.storagemanager.storage_management.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Cliente con su estado de actividad: activo = tiene al menos un alquiler ACTIVE.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientDTO {

    private Long id;
    private String fullName;
    private String email;
    private String phone;
    private String documentId;
    private String address;
    private String emergencyContact;
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private boolean active;
    private long activeRentalsCount;
}
