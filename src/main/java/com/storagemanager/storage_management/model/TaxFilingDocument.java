package com.storagemanager.storage_management.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Un documento archivado en una declaración presentada: el justificante que
 * devuelve la Sede electrónica con su CSV, el PDF de la declaración o el propio
 * fichero que se importó.
 * <p>
 * Es sólo la relación; el fichero está en {@link Document}. Mismo UNIQUE que en
 * {@link ClientDocument}: 1:N, un documento de una sola declaración.
 */
@Entity
@Table(name = "tax_filing_documents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaxFilingDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "tax_filing_id", nullable = false)
    private TaxFiling taxFiling;

    @OneToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "document_id", nullable = false, unique = true)
    private Document document;
}
