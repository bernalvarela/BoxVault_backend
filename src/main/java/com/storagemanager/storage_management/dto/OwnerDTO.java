package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.model.enums.OwnerType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * An owner together with every share they hold in units, plus - for a comunidad
 * de bienes - its members, and - for a person - the entities they belong to.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OwnerDTO {
    private Long id;
    private String fullName;
    private OwnerType type;
    private String documentId;
    private String email;
    private String phone;
    /** Domicilio fiscal, en una línea; sale en las facturas y los contratos. */
    private String address;
    private String bankAccount;
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private List<OwnershipDTO> ownerships;
    /** Members with their percentages (comunidad de bienes only). */
    private List<OwnerMembershipDTO> members;
    /** Entities this person is a member of (persons only). */
    private List<OwnerMembershipDTO> memberOf;
}
