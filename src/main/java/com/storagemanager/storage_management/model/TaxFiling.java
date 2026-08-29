package com.storagemanager.storage_management.model;

import com.storagemanager.storage_management.model.enums.TaxModel;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A tax return that was actually filed, kept so that past declarations can be
 * looked up later even after the underlying payments / expenses / shares change.
 * {@link #snapshot} holds the JSON of the report exactly as it was when filed.
 */
@Entity
@Table(name = "tax_filings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaxFiling {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaxModel model;

    /** Fiscal year ("ejercicio"). */
    @Column(name = "tax_year", nullable = false)
    private Integer year;

    /** 1-4 for the Modelo 303; null for yearly returns. */
    @Column(name = "tax_quarter")
    private Integer quarter;

    /** Owner the return belongs to (IRPF); null for returns of the entity. */
    private Long ownerId;

    @Column(length = 150)
    private String ownerName;

    @Column(nullable = false)
    private LocalDate filedDate;

    /** Main figure declared (base imponible, cuota, rendimiento...). */
    @Column(precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(length = 255)
    private String description;

    /** JSON of the report as computed when filing. */
    @Column(columnDefinition = "TEXT")
    private String snapshot;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
