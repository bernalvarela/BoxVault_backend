package com.storagemanager.storage_management.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Un documento archivado en la ficha de un cliente: lo que es suyo y no de un
 * alquiler concreto (copia del DNI, contrato de trabajo, una nómina, una foto).
 * <p>
 * Es sólo la relación; el fichero está en {@link Document}. La tabla admitiría
 * N:M, pero el UNIQUE de {@code document_id} la deja en 1:N: un documento
 * pertenece a un cliente y a uno solo. La copia firmada del contrato de alquiler
 * no va aquí, sino en {@link RentalDocument}.
 */
@Entity
@Table(name = "client_documents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClientDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @OneToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "document_id", nullable = false, unique = true)
    private Document document;
}
