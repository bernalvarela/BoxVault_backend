package com.storagemanager.storage_management.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** A person's participation in a comunidad de bienes. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OwnerMembershipDTO {
    private Long id;
    private Long entityId;
    private String entityName;
    private Long memberId;
    private String memberName;
    private BigDecimal sharePercent;
    private String notes;
}
