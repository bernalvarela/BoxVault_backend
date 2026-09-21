package com.storagemanager.storage_management.model;

import com.storagemanager.storage_management.model.enums.CommunityEntryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Un apunte del libro de una comunidad de propietarios: una cuota que entra, una
 * derrama, una factura del ascensor que sale.
 * <p>
 * Vive en su propia tabla y no en {@code expenses} a propósito, y no es manía de
 * orden: lo que la comunidad gasta no se lo deduce nadie en su IRPF -lo
 * deducible para un propietario es su cuota, ni un euro más-. Si estos apuntes
 * estuvieran entre los gastos, el 184 y el IRPF se comerían el ascensor ADEMÁS
 * de la cuota y las cifras saldrían mal hacia abajo sin que nada chirriara.
 * <p>
 * Son dos hechos sobre sujetos distintos, y por eso son dos libros.
 */
@Entity
@Table(name = "community_entries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommunityEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "community_id", nullable = false)
    private OwnersCommunity community;

    @Column(nullable = false)
    private LocalDate entryDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CommunityEntryType type;

    @Column(nullable = false, length = 200)
    private String concept;

    /** Siempre en positivo; que sume o reste lo dice el tipo. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /**
     * La unidad que paga, en cuotas y derramas; nulo en los gastos, que son del
     * edificio entero.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "storage_unit_id")
    private StorageUnit storageUnit;

    /** A quién se le paga, en los gastos. */
    @Column(length = 150)
    private String supplier;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
