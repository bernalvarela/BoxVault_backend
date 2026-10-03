package com.storagemanager.storage_management.model;

import com.storagemanager.storage_management.model.enums.BankVocabularyList;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Una palabra del vocabulario con que se leen los conceptos del banco: "NMR"
 * en las abreviaturas de número, "XULLO" en los meses (con el 7), "IBERDROLA"
 * en las palabras de gasto (con SUMINISTROS). Se guarda normalizada, como la
 * compara {@code TextMatch}: mayúsculas y sin tildes.
 */
@Entity
@Table(name = "bank_vocabulary")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankVocabularyEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "list_key", nullable = false, length = 30)
    private BankVocabularyList listKey;

    @Column(nullable = false, length = 80)
    private String word;

    /** El número del mes o el nombre de la categoría de gasto; nulo en las listas que no llevan nada. */
    @Column(name = "entry_value", length = 30)
    private String value;

    /** El orden dentro de la lista, para enseñarla como se escribió. */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;
}
