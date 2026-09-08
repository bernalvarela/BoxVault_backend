package com.storagemanager.storage_management.model;

import com.storagemanager.storage_management.model.enums.ExpenseCategory;
import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A cost incurred by the business. It is tied to a unit - a trastero, a flat or
 * a local (the IBI, electricity or insurance of the "Bajo delantero" go to the
 * local itself, which groups its trasteros) - or is general (null storageUnit),
 * in which case it counts for the business as a whole and cannot be attributed
 * to any owner.
 */
@EntityListeners(AuditingEntityListener.class)
@Entity
@Table(name = "expenses")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Expense {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * La unidad contra la que va el gasto. Obligatoria: es lo que permite
     * repartirlo por inmueble, imputarlo en el IRPF y saber a quién le toca
     * verlo (el ámbito por unidades). Las filas antiguas sin unidad se arreglan
     * con deploy/postgres/migrations/2026-09-08-gastos-con-unidad.sql.
     */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "storage_unit_id", nullable = false)
    private StorageUnit storageUnit;

    @Column(nullable = false)
    private LocalDate expenseDate;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 255)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ExpenseCategory category;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /** Quién la creó; lo rellena solo AuditingConfig. */
    @CreatedBy
    @Column(updatable = false, length = 60)
    private String createdBy;

    /** Quién la cambió por última vez. */
    @LastModifiedBy
    @Column(length = 60)
    private String updatedBy;
}
