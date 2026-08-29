package com.storagemanager.storage_management.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The share (percentage of participation) an {@link Owner} holds in a
 * {@link StorageUnit}.
 * <p>
 * Resolution rule: a unit with shares of its own has exactly those owners;
 * otherwise it inherits the (effective) shares of its parent unit. So the
 * comunidad de bienes owning 100 % of the "Bajo delantero" local owns every
 * trastero inside it, while each flat carries its own split among persons.
 * <p>
 * Shares are stored as a percentage with four decimals (1/6 = 16.6667) so the
 * usual fractions round-trip; the sum per unit is not enforced to be 100 %, the
 * UI only flags it.
 */
@Entity
@Table(name = "ownerships", uniqueConstraints =
        @UniqueConstraint(name = "uk_ownership_owner_unit", columnNames = {"owner_id", "storage_unit_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Ownership {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private Owner owner;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "storage_unit_id", nullable = false)
    private StorageUnit storageUnit;

    /** Percentage of participation, 0 &lt; share &lt;= 100, four decimals. */
    @Column(nullable = false, precision = 7, scale = 4)
    private BigDecimal sharePercent;

    @Column(length = 255)
    private String notes;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
