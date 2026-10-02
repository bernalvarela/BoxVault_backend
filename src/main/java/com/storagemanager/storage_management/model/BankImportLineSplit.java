package com.storagemanager.storage_management.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * La parte de un cargo del banco que es de una unidad: "XIAO BERNAL TERCEIROS E
 * BAIXOS", 53,40 €, son la comunidad de cada piso más la parte de los bajos. Al
 * aplicar la fila se crea un gasto por cada parte.
 */
@Entity
@Table(name = "bank_import_line_splits")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankImportLineSplit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // EAGER: la imagen nativa no puede crear proxies de Hibernate (ver UnitPriceHistory).
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "line_id", nullable = false)
    private BankImportLine line;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "storage_unit_id", nullable = false)
    private StorageUnit storageUnit;

    /** En positivo; las partes de una fila suman su importe. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Builder.Default
    private Integer position = 0;
}
