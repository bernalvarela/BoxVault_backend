package com.storagemanager.storage_management.model;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Una plantilla de contrato: el texto con el que se compone el PDF que firman
 * las partes.
 * <p>
 * El texto no está aquí sino en el almacén de ficheros ({@code storageKey}),
 * igual que los adjuntos. Son unos pocos kilobytes, así que cabrían en una
 * columna; van fuera por lo mismo que los demás documentos: la base de datos
 * guarda de qué va cada cosa y el almacén guarda los bytes, y así una copia de
 * la base sigue siendo pequeña y rápida de restaurar.
 * <p>
 * Un contrato puede decir con cuál se compone; el que no diga nada usa la
 * marcada por defecto. Una sola puede serlo a la vez.
 */
@EntityListeners(AuditingEntityListener.class)
@Entity
@Table(name = "contract_templates")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContractTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Cómo se llama en la lista: "Trastero con fianza", "Local comercial"... */
    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 255)
    private String description;

    /** Dónde está el texto en el almacén ({@code plantillas/uuid.txt}). */
    @Column(nullable = false, length = 400, unique = true)
    private String storageKey;

    /** Tamaño del texto, para enseñarlo en la lista sin ir a buscarlo. */
    private Long sizeBytes;

    /**
     * La que se usa cuando el contrato no dice nada. Sólo una la lleva: al marcar
     * otra, la anterior se desmarca ({@code ContractTemplateService}).
     */
    @Builder.Default
    private Boolean defaultTemplate = false;

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

    /** Nunca null: una plantilla sin marcar no es la de por defecto. */
    public boolean isDefaultTemplate() {
        return Boolean.TRUE.equals(defaultTemplate);
    }
}
