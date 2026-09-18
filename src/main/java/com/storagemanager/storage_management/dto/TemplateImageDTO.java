package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.model.TemplateImage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Una imagen de plantilla, con la marca que hay que escribir para colocarla. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TemplateImageDTO {

    private Long id;
    private String name;
    private String fileName;
    private String contentType;
    private Long sizeBytes;
    private LocalDateTime uploadedAt;
    /** Lo que hay que escribir en la plantilla para que salga. */
    private String mark;
    /** De dónde la baja la pantalla para enseñarla. */
    private String downloadUrl;

    public static TemplateImageDTO of(TemplateImage image) {
        return TemplateImageDTO.builder()
                .id(image.getId())
                .name(image.getName())
                .fileName(image.getFileName())
                .contentType(image.getContentType())
                .sizeBytes(image.getSizeBytes())
                .uploadedAt(image.getUploadedAt())
                .mark("[[imagen:" + image.getName() + "]]")
                .downloadUrl("/api/contract-templates/images/" + image.getId() + "/content")
                .build();
    }
}
