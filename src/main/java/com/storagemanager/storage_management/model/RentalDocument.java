package com.storagemanager.storage_management.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Un documento archivado en un alquiler: la copia firmada del contrato y lo que
 * va con ella (anexos, fotos de la entrega, justificantes).
 * <p>
 * Cuelga del alquiler y no del cliente a propósito: el contrato es de una unidad
 * y unas fechas concretas, y un mismo cliente puede tener varios —incluso de la
 * misma unidad, en épocas distintas—. Lo que es del cliente (su DNI, una nómina)
 * va en {@link ClientDocument}.
 * <p>
 * Como allí, es sólo la relación: el fichero está en {@link Document}, y el
 * UNIQUE de {@code document_id} deja la tabla en 1:N.
 */
@Entity
@Table(name = "rental_documents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RentalDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "rental_agreement_id", nullable = false)
    private RentalAgreement rentalAgreement;

    @OneToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "document_id", nullable = false, unique = true)
    private Document document;
}
