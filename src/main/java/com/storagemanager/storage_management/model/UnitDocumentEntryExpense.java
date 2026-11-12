package com.storagemanager.storage_management.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Un gasto relacionado con una entrada de documentos de una unidad: el pago de
 * la tasa que pide la notificación de licencia sale agrupado con ella, con sus
 * justificantes. Un gasto va como mucho en una entrada (UNIQUE), y los dos son
 * de la misma unidad. Es sólo la relación: quitarla no toca el gasto.
 */
@Entity
@Table(name = "unit_document_entry_expenses")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UnitDocumentEntryExpense {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "entry_id", nullable = false)
    private UnitDocumentEntry entry;

    @OneToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "expense_id", nullable = false, unique = true)
    private Expense expense;
}
