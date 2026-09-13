package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.model.Document;
import com.storagemanager.storage_management.model.TaxFilingDocument;
import com.storagemanager.storage_management.model.enums.DocumentType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Un documento archivado en una declaración presentada, aplanado igual que
 * {@link ClientDocumentDTO}: el {@code id} es el del documento, que es lo que
 * llevan las urls, y el fichero se pide por {@link #downloadUrl}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaxFilingDocumentDTO {

    private Long id;
    private Long filingId;
    private DocumentType documentType;
    private String fileName;
    private String contentType;
    private Long sizeBytes;
    private String description;
    private LocalDateTime uploadedAt;
    /** Ruta de descarga, relativa: el frontend la usa tal cual. */
    private String downloadUrl;

    public static TaxFilingDocumentDTO of(TaxFilingDocument link) {
        Long filingId = link.getTaxFiling().getId();
        Document doc = link.getDocument();
        return TaxFilingDocumentDTO.builder()
                .id(doc.getId())
                .filingId(filingId)
                .documentType(doc.getDocumentType())
                .fileName(doc.getFileName())
                .contentType(doc.getContentType())
                .sizeBytes(doc.getSizeBytes())
                .description(doc.getDescription())
                .uploadedAt(doc.getUploadedAt())
                .downloadUrl("/api/taxes/filings/" + filingId + "/documents/" + doc.getId() + "/download")
                .build();
    }
}
