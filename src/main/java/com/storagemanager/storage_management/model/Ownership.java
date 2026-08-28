package com.storagemanager.storage_management.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The share (percentage of participation) an {@link Owner} holds in either a
 * whole {@link StorageGroup} or a single {@link StorageUnit}: exactly one of the
 * two targets is set.
 * <p>
 * Resolution rule for a unit: if the unit has ownerships of its own they are its
 * owners; otherwise the ownerships of its group apply to it. So "Xiao has 1/6 of
 * the baixos" is a single group-level row, while "Bernal has 1/4 of the 3D" is a
 * unit-level row that (together with any other rows of that unit) overrides the
 * group split for that apartment.
 * <p>
 * Shares are stored as a percentage with four decimals (1/6 = 16.6667) so the
 * usual fractions round-trip; the sum per target is not enforced to be 100 %,
 * the UI only flags it.
 */
@Entity
@Table(name = "ownerships", uniqueConstraints = {
        @UniqueConstraint(name = "uk_ownership_owner_unit", columnNames = {"owner_id", "storage_unit_id"}),
        @UniqueConstraint(name = "uk_ownership_owner_group", columnNames = {"owner_id", "storage_group_id"})
})
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

    /** Set for a unit-level share; null for a group-level one. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "storage_unit_id")
    private StorageUnit storageUnit;

    /** Set for a group-level share (applies to every unit of the group without unit-level shares); null otherwise. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "storage_group_id")
    private StorageGroup storageGroup;

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

    public boolean isUnitLevel() {
        return storageUnit != null;
    }
}
