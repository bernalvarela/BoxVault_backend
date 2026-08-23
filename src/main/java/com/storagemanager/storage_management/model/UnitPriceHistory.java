package com.storagemanager.storage_management.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One entry per change of a storage unit's monthly price. The current price
 * lives on {@link StorageUnit#getBaseMonthlyRate()}; this table keeps the
 * evolution over time so past prices are never lost when the price changes.
 */
@Entity
@Table(name = "unit_price_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UnitPriceHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "storage_unit_id", nullable = false)
    private StorageUnit storageUnit;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal monthlyPrice;

    /** Date from which this price applies. */
    @Column(nullable = false)
    private LocalDate effectiveFrom;

    @Column(length = 255)
    private String notes;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
