package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.model.Invoice;
import com.storagemanager.storage_management.model.enums.InvoiceType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Una factura emitida, para la pantalla: sus cifras tal como se emitieron, a qué
 * factura rectifica si es el caso, y dónde está su PDF.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceDTO {

    private Long id;
    private String number;
    private InvoiceType type;
    private LocalDate issuedOn;
    private BigDecimal base;
    private BigDecimal vat;
    private BigDecimal total;
    private String clientName;
    private String reason;
    /** El número de la factura rectificada; null en una ordinaria. */
    private String rectifies;
    private String fileName;
    /** Ruta de descarga del PDF, relativa; el frontend la usa tal cual. */
    private String downloadUrl;

    public static InvoiceDTO of(Invoice invoice) {
        Long rentalId = invoice.getPayment().getRentalAgreement() != null
                ? invoice.getPayment().getRentalAgreement().getId() : null;
        Long documentId = invoice.getDocument() != null ? invoice.getDocument().getId() : null;
        return InvoiceDTO.builder()
                .id(invoice.getId())
                .number(invoice.getNumber())
                .type(invoice.getType())
                .issuedOn(invoice.getIssuedOn())
                .base(invoice.getBase())
                .vat(invoice.getVat())
                .total(invoice.getTotal())
                .clientName(invoice.getClientName())
                .reason(invoice.getReason())
                .rectifies(invoice.getRectifies() != null ? invoice.getRectifies().getNumber() : null)
                .fileName(invoice.getDocument() != null ? invoice.getDocument().getFileName() : null)
                .downloadUrl(rentalId != null && documentId != null
                        ? "/api/rentals/" + rentalId + "/documents/" + documentId + "/download" : null)
                .build();
    }
}
