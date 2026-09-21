package com.storagemanager.storage_management.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * El presupuesto anual de una comunidad, aprobado en junta.
 * <p>
 * De aquí sale la cuota de cada unidad -{@code coeficiente × importe ÷ 12}- en
 * vez de guardarla repetida piso por piso: una cuota es un dato derivado, y el
 * día que la junta apruebe otro presupuesto todas se recalculan solas.
 */
@Entity
@Table(name = "community_budgets",
        uniqueConstraints = @UniqueConstraint(name = "uk_community_budgets",
                columnNames = {"community_id", "budget_year"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommunityBudget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "community_id", nullable = false)
    private OwnersCommunity community;

    // "year" es palabra reservada en H2 (y frágil en general): la columna se
    // llama budget_year y el campo se queda con el nombre que se lee bien.
    @Column(name = "budget_year", nullable = false)
    private Integer year;

    /** Lo aprobado para todo el ejercicio. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal annualAmount;

    @Column(columnDefinition = "TEXT")
    private String notes;
}
