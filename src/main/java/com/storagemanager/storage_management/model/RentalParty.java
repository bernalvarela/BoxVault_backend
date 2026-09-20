package com.storagemanager.storage_management.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.storagemanager.storage_management.model.enums.PartyRole;
import jakarta.persistence.*;
import lombok.*;

/**
 * Una persona que firma un contrato, y en calidad de qué.
 * <p>
 * Antes un contrato tenía como mucho un inquilino, un segundo titular y un
 * fiador, cada uno en su columna. Los contratos reales no son así: el 3D lo
 * firman dos inquilinos y un fiador, y nada impide que mañana sean tres. Con
 * columnas fijas, el cuarto no cabe y hay que tocar el esquema; con una lista,
 * cabe el que haga falta.
 * <p>
 * El orden importa y por eso se guarda: el primer ARRENDATARIO es el titular
 * -el de los cobros y las facturas- y en el contrato impreso la gente aparece
 * en el orden en que se la puso, que suele ser el de la escritura o el del
 * documento que se está copiando.
 */
@Entity
@Table(name = "rental_parties")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RentalParty {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * El contrato al que pertenece. No se serializa: se llega a las partes desde
     * el contrato, y de vuelta sería un bucle infinito en el JSON.
     */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rental_agreement_id", nullable = false)
    private RentalAgreement rentalAgreement;

    /** Quién es: una ficha de cliente de las de siempre. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PartyRole role;

    /** Su sitio en la lista, empezando por 0. */
    @Column(nullable = false)
    @Builder.Default
    private Integer position = 0;
}
