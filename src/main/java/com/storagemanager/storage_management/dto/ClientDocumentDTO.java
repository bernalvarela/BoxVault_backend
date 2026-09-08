package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.model.ClientDocument;
import com.storagemanager.storage_management.model.enums.DocumentType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Ficha de un documento archivado. No lleva la clave del objeto en el almacén:
 * el fichero se pide por {@link #downloadUrl}, que sirve el propio backend.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientDocumentDTO {

    private Long id;
    private Long clientId;
    private DocumentType documentType;
    private String fileName;
    private String contentType;
    private Long sizeBytes;
    private String description;
    private LocalDateTime uploadedAt;
    /** Ruta de descarga, relativa: el frontend la usa tal cual. */
    private String downloadUrl;

    public static ClientDocumentDTO of(ClientDocument doc) {
        Long clientId = doc.getClient().getId();
        return ClientDocumentDTO.builder()
                .id(doc.getId())
                .clientId(clientId)
                .documentType(doc.getDocumentType())
                .fileName(doc.getFileName())
                .contentType(doc.getContentType())
                .sizeBytes(doc.getSizeBytes())
                .description(doc.getDescription())
                .uploadedAt(doc.getUploadedAt())
                .downloadUrl("/api/clients/" + clientId + "/documents/" + doc.getId() + "/download")
                .build();
    }
}
