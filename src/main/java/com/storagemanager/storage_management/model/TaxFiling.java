package com.storagemanager.storage_management.model;

import com.storagemanager.storage_management.model.enums.TaxModel;
import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
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
@EntityListeners(AuditingEntityListener.class)
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

    /**
     * Cifra principal de la declaración: para el Modelo 303 reconstruido desde los
     * pagos, lo que se ingresó de verdad en la AEAT; para el resto, la cifra
     * declarada (base imponible, cuota, rendimiento...).
     */
    @Column(precision = 12, scale = 2)
    private BigDecimal amount;

    /**
     * El gasto que acredita el pago de esta declaración (el cargo de la AEAT con su
     * NRC), cuando lo hay. Se guarda el id y no una relación: el registro tiene que
     * sobrevivir a que se borre el gasto, aunque entonces pierda el rastro.
     */
    @Column(name = "expense_id")
    private Long expenseId;

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

    /** Quién la creó; lo rellena solo AuditingConfig. */
    @CreatedBy
    @Column(updatable = false, length = 60)
    private String createdBy;

    /** Quién la cambió por última vez. */
    @LastModifiedBy
    @Column(length = 60)
    private String updatedBy;
}
