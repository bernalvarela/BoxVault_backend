package com.storagemanager.storage_management.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * El papel de un apunte: la factura del ascensor, el recibo del seguro.
 * <p>
 * Misma forma que {@code rental_documents} y {@code client_documents} — el
 * fichero vive una sola vez en {@link Document} y esto sólo dice de quién es—.
 */
@Entity
@Table(name = "community_entry_documents",
        uniqueConstraints = @UniqueConstraint(name = "uk_community_entry_documents",
                columnNames = {"community_entry_id", "document_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommunityEntryDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_entry_id", nullable = false)
    private CommunityEntry entry;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;
}
