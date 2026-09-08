package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.model.Document;
import com.storagemanager.storage_management.model.RentalDocument;
import com.storagemanager.storage_management.model.enums.DocumentType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Un documento de un alquiler, aplanado. Igual que {@link ClientDocumentDTO},
 * pero colgando del contrato.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RentalDocumentDTO {

    private Long id;
    private Long rentalAgreementId;
    private DocumentType documentType;
    private String fileName;
    private String contentType;
    private Long sizeBytes;
    private String description;
    private LocalDateTime uploadedAt;
    /** Ruta de descarga, relativa: el frontend la usa tal cual. */
    private String downloadUrl;

    public static RentalDocumentDTO of(RentalDocument link) {
        Long rentalId = link.getRentalAgreement().getId();
        Document doc = link.getDocument();
        return RentalDocumentDTO.builder()
                .id(doc.getId())
                .rentalAgreementId(rentalId)
                .documentType(doc.getDocumentType())
                .fileName(doc.getFileName())
                .contentType(doc.getContentType())
                .sizeBytes(doc.getSizeBytes())
                .description(doc.getDescription())
                .uploadedAt(doc.getUploadedAt())
                .downloadUrl("/api/rentals/" + rentalId + "/documents/" + doc.getId() + "/download")
                .build();
    }
}
