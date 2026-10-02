package com.storagemanager.storage_management.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * La proporción de un cargo que una regla aprendida da a cada unidad. Se guarda
 * como proporción y no como importe para que valga aunque la cuota cambie: si
 * la comunidad sube, el reparto sigue siendo el mismo.
 */
@Entity
@Table(name = "bank_match_rule_splits")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankMatchRuleSplit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rule_id", nullable = false)
    private BankMatchRule rule;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "storage_unit_id", nullable = false)
    private StorageUnit storageUnit;

    /** De 0 a 1; las de una regla suman 1. */
    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal share;

    @Builder.Default
    private Integer position = 0;
}
