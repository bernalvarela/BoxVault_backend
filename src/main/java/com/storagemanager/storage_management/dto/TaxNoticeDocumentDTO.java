package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.model.Document;
import com.storagemanager.storage_management.model.TaxNoticeDocument;
import com.storagemanager.storage_management.model.enums.DocumentType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Un documento archivado en una notificación de la AEAT, aplanado igual que
 * {@link TaxFilingDocumentDTO}: el {@code id} es el del documento.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaxNoticeDocumentDTO {

    private Long id;
    private Long noticeId;
    private DocumentType documentType;
    private String fileName;
    private String contentType;
    private Long sizeBytes;
    private String description;
    private LocalDateTime uploadedAt;
    /** Ruta de descarga, relativa: el frontend la usa tal cual. */
    private String downloadUrl;

    public static TaxNoticeDocumentDTO of(TaxNoticeDocument link) {
        Long noticeId = link.getTaxNotice().getId();
        Document doc = link.getDocument();
        return TaxNoticeDocumentDTO.builder()
                .id(doc.getId())
                .noticeId(noticeId)
                .documentType(doc.getDocumentType())
                .fileName(doc.getFileName())
                .contentType(doc.getContentType())
                .sizeBytes(doc.getSizeBytes())
                .description(doc.getDescription())
                .uploadedAt(doc.getUploadedAt())
                .downloadUrl("/api/taxes/notices/" + noticeId + "/documents/" + doc.getId() + "/download")
                .build();
    }
}
