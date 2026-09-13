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

    /** Lo pagado por el gasto, IVA incluido (igual que el precio de un alquiler). */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    /**
     * Cuota de IVA soportado incluida en {@link #amount} y deducible en el Modelo
     * 303. Nula o cero cuando el gasto no lleva IVA (el IBI) o cuando no se
     * conoce: lo que no se declara aquí no se deduce en el 303 y sigue contando
     * entero como coste en el IRPF.
     */
    @Column(name = "vat_amount", precision = 10, scale = 2)
    private BigDecimal vatAmount;

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

    /** La cuota de IVA soportado, nunca nula: cero cuando el gasto no la declara. */
    public BigDecimal deductibleVat() {
        return vatAmount == null ? BigDecimal.ZERO.setScale(2) : vatAmount;
    }

    /**
     * Lo que cuesta el gasto de verdad: el importe sin el IVA que se deduce en el
     * 303. Es lo que se imputa como gasto deducible en el IRPF, para no restar
     * dos veces la misma cuota.
     */
    public BigDecimal netAmount() {
        return amount == null ? BigDecimal.ZERO.setScale(2) : amount.subtract(deductibleVat());
    }
}
