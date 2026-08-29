package com.storagemanager.storage_management.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storagemanager.storage_management.model.enums.UnitKind;
import com.storagemanager.storage_management.model.enums.UnitStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A unit: a storage unit ("trastero"), an apartment or a local (business
 * premises). Units form a tree through {@link #parent}: the trasteros sit inside
 * the "Bajo delantero" local. The topmost unit of the chain ({@link #getRoot()})
 * is what the statistics filter by, and a unit without shares of its own inherits
 * the owners of its parent.
 */
@Entity
@Table(name = "storage_units")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StorageUnit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String unitNumber;

    /**
     * Storage unit, apartment or local. Nullable at the database level so rows created
     * before the column existed survive the schema update; a null is read as
     * {@link UnitKind#STORAGE_UNIT}.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    @Builder.Default
    private UnitKind kind = UnitKind.STORAGE_UNIT;

    /** The unit this one sits inside (a trastero inside a local); null for a top-level unit. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "parent_unit_id")
    private StorageUnit parent;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private Double sizeSquareMeters;

    @Column(length = 50)
    private String dimensions;

    @Column(length = 100)
    private String location;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal baseMonthlyRate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private UnitStatus status = UnitStatus.AVAILABLE;

    @Column(columnDefinition = "TEXT")
    private String description;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /** Never null: legacy rows without a kind are storage units. */
    public UnitKind getKind() {
        return kind == null ? UnitKind.STORAGE_UNIT : kind;
    }

    /** Whether this unit's prices carry 21% VAT (storage units, locales) or are exempt (apartments). Serialised as {@code vatApplicable}. */
    @JsonProperty("vatApplicable")
    public boolean isVatApplicable() {
        return getKind().isVatApplicable();
    }

    /** A local that groups other units rather than being rented itself. Serialised as {@code container}. */
    @JsonProperty("container")
    public boolean isContainer() {
        return getKind() == UnitKind.PREMISES;
    }

    /** Topmost unit of the parent chain (this unit when it has no parent). */
    public StorageUnit rootUnit() {
        StorageUnit u = this;
        int guard = 0;
        while (u.getParent() != null && guard++ < 32) {
            u = u.getParent();
        }
        return u;
    }

    /** Id of the root unit; what statistics and listings filter by. */
    @JsonProperty("rootId")
    public Long getRootId() {
        return rootUnit().getId();
    }

    /** Small reference to the root unit, serialised as {@code root}. */
    @JsonProperty("root")
    public UnitRef getRoot() {
        return UnitRef.of(rootUnit());
    }

    /** Lightweight reference to a unit for JSON payloads. */
    public record UnitRef(Long id, String unitNumber, String name, UnitKind kind) {
        public static UnitRef of(StorageUnit u) {
            return u == null ? null : new UnitRef(u.getId(), u.getUnitNumber(), u.getName(), u.getKind());
        }
    }
}
