package com.storagemanager.storage_management.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Un documento archivado en una notificación de la AEAT: la notificación en sí,
 * el acuerdo, las alegaciones presentadas o el justificante del pago.
 * <p>
 * Es sólo la relación; el fichero está en {@link Document}. Mismo UNIQUE que en
 * {@link TaxFilingDocument}: un documento de una sola notificación.
 */
@Entity
@Table(name = "tax_notice_documents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaxNoticeDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "tax_notice_id", nullable = false)
    private TaxNotice taxNotice;

    @OneToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "document_id", nullable = false, unique = true)
    private Document document;
}
