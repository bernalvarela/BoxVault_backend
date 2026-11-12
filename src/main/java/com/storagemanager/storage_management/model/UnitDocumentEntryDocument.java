package com.storagemanager.storage_management.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Un fichero de una entrada de documentos de una unidad. Es sólo la relación;
 * el fichero está en {@link Document}, y un documento es de una sola entrada.
 */
@Entity
@Table(name = "unit_document_entry_documents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UnitDocumentEntryDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "entry_id", nullable = false)
    private UnitDocumentEntry entry;

    @OneToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "document_id", nullable = false, unique = true)
    private Document document;
}
