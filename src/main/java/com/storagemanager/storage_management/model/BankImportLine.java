package com.storagemanager.storage_management.model;

import com.storagemanager.storage_management.model.enums.BankLineAction;
import com.storagemanager.storage_management.model.enums.BankLineStatus;
import com.storagemanager.storage_management.model.enums.CommunityEntryType;
import com.storagemanager.storage_management.model.enums.ExpenseCategory;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Un movimiento de un extracto y lo que se va a hacer con él.
 * <p>
 * Lo propone la aplicación al importar ({@code BankMatcher}) y lo puede cambiar
 * quien revisa. Según {@link #action}, se usan unos campos u otros: el contrato y
 * el mes para un cobro; la categoría y la unidad para un gasto; el tipo y la
 * unidad para un apunte de la comunidad. Al aplicarlo, queda anotado lo que
 * creó ({@link #paymentId}, {@link #expenseId}, {@link #communityEntryId}).
 */
@Entity
@Table(name = "bank_import_lines")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankImportLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "import_id", nullable = false)
    private BankImport bankImport;

    /** La fila del fichero, empezando en 1, para encontrarla en el original. */
    @Column(nullable = false)
    private Integer lineNumber;

    @Column(name = "movement_date", nullable = false)
    private LocalDate date;

    private LocalDate valueDate;

    @Column(nullable = false, length = 500)
    private String concept;

    /** Positivo si entra dinero, negativo si sale. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(precision = 12, scale = 2)
    private BigDecimal balance;

    @Column(nullable = false, length = 64)
    private String fingerprint;

    /** El identificador del movimiento en el banco, si lo da. */
    @Column(length = 60)
    private String bankReference;

    /** Ya estaba en otro extracto importado: se descarta de entrada. */
    @Builder.Default
    private Boolean duplicate = false;

    /**
     * El ingreso ya estaba apuntado a mano como cobro: se ignora de entrada y
     * {@link #paymentId} dice cuál es ese cobro.
     */
    @Builder.Default
    private Boolean alreadyRecorded = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private BankLineStatus status = BankLineStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private BankLineAction action = BankLineAction.NONE;

    /** Por qué se propone esto: "por el nombre de Ana Gómez", "por el importe"... */
    @Column(length = 300)
    private String reason;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "rental_agreement_id")
    private RentalAgreement rentalAgreement;

    private Integer periodYear;
    private Integer periodMonth;

    /**
     * Cuántas mensualidades seguidas cubre el cobro, desde
     * {@link #periodYear}/{@link #periodMonth}: "septiembre y octubre" por el
     * doble de la mensualidad son dos.
     */
    @Builder.Default
    private Integer periodCount = 1;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ExpenseCategory expenseCategory;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "storage_unit_id")
    private StorageUnit storageUnit;

    /**
     * Un gasto repartido entre varias unidades, en vez de ir a
     * {@link #storageUnit}: una parte por unidad, que suman el cargo.
     */
    @OneToMany(mappedBy = "line", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC, id ASC")
    @Builder.Default
    private java.util.List<BankImportLineSplit> splits = new java.util.ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private CommunityEntryType communityEntryType;

    /** Lo asignó una persona: al aplicarlo se recuerda como regla. */
    @Builder.Default
    private Boolean learn = false;

    private Long paymentId;
    private Long expenseId;
    private Long communityEntryId;

    @Column(length = 500)
    private String error;

    public boolean isIncome() {
        return amount != null && amount.signum() > 0;
    }
}
