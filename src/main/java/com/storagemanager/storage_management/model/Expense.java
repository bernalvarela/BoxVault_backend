package com.storagemanager.storage_management.model;

import com.storagemanager.storage_management.model.enums.ExpenseCategory;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A cost incurred by the business. It can be tied to a specific storage unit
 * (e.g. a repair inside one trastero) or be general (null storageUnit): taxes,
 * electricity, insurance...
 * <p>
 * A general expense can still be attributed to a {@link StorageGroup} (the
 * electricity bill of one building, its IBI...), so that statistics filtered by
 * group include it. When a unit is set, the expense's group is the unit's group
 * and {@code storageGroup} stays null.
 */
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

    /** Null means a general expense not attributable to a single unit. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "storage_unit_id")
    private StorageUnit storageUnit;

    /** Only meaningful for general expenses (storageUnit == null); null means "not attributable to any group". */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "storage_group_id")
    private StorageGroup storageGroup;

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
}
