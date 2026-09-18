package com.storagemanager.storage_management.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Lo que se manda al crear o guardar una plantilla de contrato. */
@Data
public class ContractTemplateRequest {

    @NotBlank(message = "La plantilla necesita un nombre")
    @Size(max = 120, message = "El nombre no puede pasar de 120 caracteres")
    private String name;

    @Size(max = 255, message = "La descripción no puede pasar de 255 caracteres")
    private String description;

    @NotBlank(message = "La plantilla no puede estar vacía")
    private String content;

    /** Marcarla como la que se usa cuando el contrato no dice cuál. */
    private Boolean makeDefault;
}
