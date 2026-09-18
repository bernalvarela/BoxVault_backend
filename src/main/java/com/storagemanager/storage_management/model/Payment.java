package com.storagemanager.storage_management.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.storagemanager.storage_management.model.enums.PaymentMethod;
import com.storagemanager.storage_management.model.enums.PaymentStatus;
import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@EntityListeners(AuditingEntityListener.class)
@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "rental_agreement_id", nullable = false)
    private RentalAgreement rentalAgreement;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "storage_unit_id", nullable = false)
    private StorageUnit storageUnit;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @Column(nullable = false)
    private Integer billingPeriodMonth;

    @Column(nullable = false)
    private Integer billingPeriodYear;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amountDue;

    @Column(precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal amountPaid = BigDecimal.ZERO;

    @Column(nullable = false)
    private LocalDate dueDate;

    private LocalDate paymentDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private PaymentStatus status = PaymentStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private PaymentMethod paymentMethod;

    @Column(length = 100)
    private String transactionReference;

    @Column(columnDefinition = "TEXT")
    private String notes;

    /**
     * Número de la factura emitida por esta mensualidad ({@code A2026/0007}) y
     * el día en que se expidió; nulos mientras no se haya emitido ninguna.
     * <p>
     * Se guardan aquí, y no se calculan al vuelo, porque una vez entregada la
     * factura su número ya no puede cambiar: es lo que la hace correlativa.
     */
    @Column(length = 30, unique = true)
    private String invoiceNumber;

    private LocalDate invoicedAt;

    /**
     * El PDF archivado de esa factura. Tenerlo apuntado evita emitir dos veces
     * lo mismo: si ya está, se devuelve el que se entregó, no uno nuevo.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "invoice_document_id")
    @JsonIgnore
    private Document invoiceDocument;

    /** Id del documento de la factura, para que el frontend pueda abrirlo. */
    @JsonProperty("invoiceDocumentId")
    public Long getInvoiceDocumentId() {
        return invoiceDocument == null ? null : invoiceDocument.getId();
    }

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
}
