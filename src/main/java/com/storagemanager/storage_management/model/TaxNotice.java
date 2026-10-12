package com.storagemanager.storage_management.model;

import com.storagemanager.storage_management.model.enums.TaxModel;
import com.storagemanager.storage_management.model.enums.TaxNoticeKind;
import com.storagemanager.storage_management.model.enums.TaxNoticeStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Una notificación de la AEAT: un requerimiento, una propuesta de sanción, la
 * sanción, un recargo... Va con el titular, el modelo y el ejercicio a los que
 * se refiere (una sanción del 184 de 2024 a la comunidad de bienes) y, si ya
 * está registrada, con la declaración presentada ({@link #filingId}).
 * <p>
 * Guarda lo que hace falta para no perder un plazo: cuándo llegó, hasta cuándo
 * se puede contestar y hasta cuándo pagar, el importe y el reducido por pronto
 * pago, y el gasto con el que se pagó. Los documentos (la notificación, el
 * acuerdo, el justificante del pago) van en {@link TaxNoticeDocument}.
 * <p>
 * La declaración y el gasto se guardan por id y no como relación, igual que en
 * {@link TaxFiling}: la notificación tiene que sobrevivir a que se borre uno u otro.
 */
@EntityListeners(AuditingEntityListener.class)
@Entity
@Table(name = "tax_notices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaxNotice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TaxNoticeKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private TaxNoticeStatus status = TaxNoticeStatus.PENDIENTE;

    /** El modelo al que se refiere; null si no es de ninguno en concreto. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private TaxModel model;

    /** Ejercicio al que se refiere. */
    @Column(name = "tax_year")
    private Integer year;

    /** Trimestre, en las del Modelo 303. */
    @Column(name = "tax_quarter")
    private Integer quarter;

    /** A quién va dirigida: la comunidad de bienes o la persona. */
    private Long ownerId;

    @Column(length = 150)
    private String ownerName;

    /** La declaración presentada de la que trae causa, si está registrada. */
    @Column(name = "filing_id")
    private Long filingId;

    /** Número de expediente o referencia de la notificación. */
    @Column(length = 100)
    private String reference;

    @Column(length = 255)
    private String description;

    /** Fecha en la que se recibió (la de la notificación electrónica o el acuse). */
    @Column(nullable = false)
    private LocalDate receivedOn;

    /** Hasta cuándo se puede contestar o alegar. */
    private LocalDate responseDeadline;

    /** Hasta cuándo se puede pagar en periodo voluntario. */
    private LocalDate paymentDeadline;

    /** Lo que se pide pagar. */
    @Column(precision = 12, scale = 2)
    private BigDecimal amount;

    /** Lo que queda si se paga en plazo y no se recurre (la reducción del 25 % de una sanción). */
    @Column(precision = 12, scale = 2)
    private BigDecimal reducedAmount;

    private LocalDate paidOn;

    @Column(precision = 12, scale = 2)
    private BigDecimal paidAmount;

    /** El gasto que recoge el pago (categoría SANCIONES). */
    @Column(name = "expense_id")
    private Long expenseId;

    @Column(columnDefinition = "TEXT")
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
