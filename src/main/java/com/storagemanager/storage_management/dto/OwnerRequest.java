package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.model.enums.OwnerType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class OwnerRequest {

    @NotBlank(message = "Owner name is required")
    @Size(max = 150, message = "Owner name must be at most 150 characters")
    private String fullName;

    /** PERSON (default) or COMUNIDAD_DE_BIENES. */
    private OwnerType type;

    @Size(max = 50, message = "Document id must be at most 50 characters")
    private String documentId;

    @Email(message = "Email must be valid")
    @Size(max = 150, message = "Email must be at most 150 characters")
    private String email;

    @Size(max = 50, message = "Phone must be at most 50 characters")
    private String phone;

    @Size(max = 50, message = "Bank account must be at most 50 characters")
    private String bankAccount;

    private String notes;

    /**
     * Members of a comunidad de bienes with their percentages; replaces the current
     * list when given. Ignored for persons.
     */
    @Valid
    private List<MemberRequest> members;

    @Data
    public static class MemberRequest {
        @NotNull(message = "Member is required")
        private Long ownerId;

        @NotNull(message = "Share is required")
        @DecimalMin(value = "0.0001", message = "Share must be greater than zero")
        @DecimalMax(value = "100", message = "Share cannot exceed 100 %")
        @Digits(integer = 3, fraction = 4, message = "Share admits at most four decimals")
        private BigDecimal sharePercent;

        @Size(max = 255, message = "Notes must be at most 255 characters")
        private String notes;
    }
}
