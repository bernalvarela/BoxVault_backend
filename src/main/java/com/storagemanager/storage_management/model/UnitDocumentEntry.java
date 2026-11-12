package com.storagemanager.storage_management.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Una entrada de documentos de una unidad: agrupa los ficheros que van juntos
 * bajo un título ("Notificación de licencia", con sus tres PDF). Los ficheros
 * van en {@link UnitDocumentEntryDocument}; una entrada tiene uno o más.
 * <p>
 * Es para los papeles que son de la unidad y no de un pago concreto; el
 * justificante de un pago va en el gasto ({@link ExpenseDocument}).
 */
@EntityListeners(AuditingEntityListener.class)
@Entity
@Table(name = "unit_document_entries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UnitDocumentEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // EAGER: la imagen nativa no puede crear proxies de Hibernate (ver UnitPriceHistory).
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "storage_unit_id", nullable = false)
    private StorageUnit storageUnit;

    @Column(nullable = false, length = 150)
    private String title;

    /** La fecha del papel (la de la notificación, la del certificado); la elige quien lo apunta. */
    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    @Column(length = 1000)
    private String notes;

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
