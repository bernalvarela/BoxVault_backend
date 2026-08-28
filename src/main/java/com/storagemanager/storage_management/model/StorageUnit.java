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
 * A rentable unit. Historically only storage units ("trasteros") existed, hence the
 * name; {@link #kind} now distinguishes storage units from apartments, which share
 * the same attributes for now but are VAT exempt.
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
     * Storage unit or apartment. Nullable at the database level so rows created
     * before the column existed survive the schema update; a null is read as
     * {@link UnitKind#STORAGE_UNIT} and {@code DataSeeder} backfills it at start-up.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    @Builder.Default
    private UnitKind kind = UnitKind.STORAGE_UNIT;

    /**
     * Group (building / premises) this unit belongs to. Nullable at the database
     * level so existing rows survive the schema update; {@code DataSeeder} moves
     * any ungrouped unit into the default group at start-up and the API always
     * requires a group when creating or updating a unit.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "storage_group_id")
    private StorageGroup storageGroup;

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

    /** Whether this unit's prices carry 21% VAT (storage units) or are exempt (apartments). Serialised as {@code vatApplicable}. */
    @JsonProperty("vatApplicable")
    public boolean isVatApplicable() {
        return getKind().isVatApplicable();
    }
}
