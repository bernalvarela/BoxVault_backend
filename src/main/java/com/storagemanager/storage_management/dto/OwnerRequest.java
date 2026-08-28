package com.storagemanager.storage_management.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class OwnerRequest {

    @NotBlank(message = "Owner name is required")
    @Size(max = 150, message = "Owner name must be at most 150 characters")
    private String fullName;

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
}
