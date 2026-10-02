package com.storagemanager.storage_management.model;

import com.storagemanager.storage_management.model.enums.BankProfileContext;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Cómo es el extracto de una cuenta: en qué fila está la cabecera, qué columna
 * es cada cosa y cómo se escriben fechas e importes.
 * <p>
 * Cada banco exporta a su manera -BBVA de una forma, el banco de la comunidad
 * de otra- y ni siquiera un mismo banco es igual en xls que en csv. En vez de
 * escribir un lector por banco, se describe el fichero una vez y la aplicación
 * lo lee con esa descripción. Las columnas van por posición (A = 0) y no por el
 * nombre de la cabecera, que cambia con el idioma de la banca en línea.
 */
@EntityListeners(AuditingEntityListener.class)
@Entity
@Table(name = "bank_import_profiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankImportProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** "BBVA pisos", "BBVA comunidad de bienes", "Abanca comunidad"... */
    @Column(nullable = false, length = 120, unique = true)
    private String name;

    @Column(length = 80)
    private String bankName;

    /** La cuenta, para reconocerla: el IBAN o sus últimas cifras. */
    @Column(length = 120)
    private String accountLabel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BankProfileContext context;

    /** La comunidad de propietarios cuya cuenta es, si el contexto es COMUNIDAD. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "community_id")
    private OwnersCommunity community;

    /** Unidad a la que van los gastos que no se reconozcan de otra. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "default_unit_id")
    private StorageUnit defaultUnit;

    /** Juego de caracteres de los csv (UTF-8, windows-1252...). */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String encoding = "UTF-8";

    /** Fila de la cabecera, empezando en 1; 0 = sin cabecera. */
    @Column(nullable = false)
    @Builder.Default
    private Integer headerRow = 1;

    @Column(nullable = false)
    private Integer dateColumn;
    private Integer valueDateColumn;
    @Column(nullable = false)
    private Integer conceptColumn;
    private Integer conceptExtraColumn;
    /** Importe con signo; si es nulo, se usan cargo y abono. */
    private Integer amountColumn;
    private Integer debitColumn;
    private Integer creditColumn;
    private Integer balanceColumn;
    /** El identificador único del movimiento, si el banco lo da (la "Remesa" de BBVA). */
    private Integer referenceColumn;

    /** Cómo escribe las fechas cuando son texto: dd/MM/yyyy, dd-MM-yy... */
    @Column(nullable = false, length = 30)
    @Builder.Default
    private String dateFormat = "dd/MM/yyyy";

    /** 1.234,56 (coma decimal) o 1,234.56. */
    @Column(nullable = false)
    @Builder.Default
    private Boolean decimalComma = true;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @CreatedBy
    @Column(updatable = false, length = 60)
    private String createdBy;

    @LastModifiedBy
    @Column(length = 60)
    private String updatedBy;
}
