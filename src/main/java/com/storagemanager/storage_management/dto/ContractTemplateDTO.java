package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.model.ContractTemplate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Una plantilla de contrato. El texto sólo viaja cuando se pide una en concreto
 * (la pantalla de edición); la lista va sin él.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractTemplateDTO {

    private Long id;
    private String name;
    private String description;
    private Long sizeBytes;
    private boolean defaultTemplate;
    private LocalDateTime updatedAt;
    private String updatedBy;
    /** El texto de la plantilla; null en los listados. */
    private String content;

    public static ContractTemplateDTO of(ContractTemplate template) {
        return ContractTemplateDTO.builder()
                .id(template.getId())
                .name(template.getName())
                .description(template.getDescription())
                .sizeBytes(template.getSizeBytes())
                .defaultTemplate(template.isDefaultTemplate())
                .updatedAt(template.getUpdatedAt())
                .updatedBy(template.getUpdatedBy())
                .build();
    }

    public static ContractTemplateDTO withContent(ContractTemplate template, String content) {
        ContractTemplateDTO dto = of(template);
        dto.setContent(content);
        return dto;
    }
}
