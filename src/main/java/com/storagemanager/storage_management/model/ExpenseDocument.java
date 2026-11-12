package com.storagemanager.storage_management.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Un documento archivado en un gasto: el justificante del pago, la factura o el
 * recibo (el de la tasa de una licencia, el del IBI...).
 * <p>
 * Es sólo la relación; el fichero está en {@link Document}. Mismo UNIQUE que en
 * {@link TaxNoticeDocument}: un documento es de un solo gasto.
 */
@Entity
@Table(name = "expense_documents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExpenseDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "expense_id", nullable = false)
    private Expense expense;

    @OneToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "document_id", nullable = false, unique = true)
    private Document document;
}
