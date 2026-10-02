package com.storagemanager.storage_management.model;

import com.storagemanager.storage_management.model.enums.BankLineAction;
import com.storagemanager.storage_management.model.enums.CommunityEntryType;
import com.storagemanager.storage_management.model.enums.ExpenseCategory;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Algo aprendido de una revisión: si el concepto de un movimiento lleva estas
 * palabras, es esto.
 * <p>
 * Sirve para lo que el nombre no resuelve: la madre que paga el piso del hijo
 * desde su cuenta, el recibo del seguro que siempre es de la misma unidad. Se
 * crea solo cuando alguien corrige una propuesta y la aplica, y se puede borrar.
 * Para un cobro se guarda el cliente y no el contrato: el contrato cambia y la
 * persona que paga suele seguir siendo la misma.
 */
@EntityListeners(AuditingEntityListener.class)
@Entity
@Table(name = "bank_match_rules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankMatchRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Las palabras del concepto, normalizadas: todas tienen que estar. */
    @Column(nullable = false, length = 200)
    private String pattern;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BankLineAction action;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "client_id")
    private Client client;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ExpenseCategory expenseCategory;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "storage_unit_id")
    private StorageUnit storageUnit;

    /** Si el gasto se reparte entre varias unidades: la proporción de cada una. */
    @OneToMany(mappedBy = "rule", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("position ASC, id ASC")
    @Builder.Default
    private java.util.List<BankMatchRuleSplit> splits = new java.util.ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private CommunityEntryType communityEntryType;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @CreatedBy
    @Column(updatable = false, length = 60)
    private String createdBy;
}
