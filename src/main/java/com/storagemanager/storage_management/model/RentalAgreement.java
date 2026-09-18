package com.storagemanager.storage_management.model;

import com.storagemanager.storage_management.model.enums.RentalStatus;
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
@Table(name = "rental_agreements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RentalAgreement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String agreementNumber;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "storage_unit_id", nullable = false)
    private StorageUnit storageUnit;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    /** Second tenant of the same contract (co-titular), if any. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "co_client_id")
    private Client coClient;

    @Column(nullable = false)
    private LocalDate startDate;

    private LocalDate endDate;

    @Column(nullable = false)
    @Builder.Default
    private Integer billingDayOfMonth = 1;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal monthlyRent;

    @Column(precision = 10, scale = 2)
    private BigDecimal securityDeposit;

    @Builder.Default
    private Boolean depositPaid = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private RentalStatus status = RentalStatus.ACTIVE;

    @Builder.Default
    private Boolean autoRenew = true;

    /**
     * Si de este contrato se emiten facturas: al registrar un cobro sale sola la
     * factura de esa mensualidad, con su número de serie.
     * <p>
     * Se decide por contrato y no por unidad porque no depende del trastero sino
     * de quién lo alquila: una empresa necesita la factura para deducirse el IVA
     * y un particular no la pide nunca. Por defecto no: una factura emitida
     * consume un número de la serie y ya no se puede deshacer, así que se marca
     * a conciencia. En un alquiler de vivienda no se puede marcar: está exento.
     */
    @Builder.Default
    private Boolean generatesInvoices = false;

    /** Nunca null: un contrato sin marcar no factura. */
    public boolean invoices() {
        return Boolean.TRUE.equals(generatesInvoices);
    }

    @Column(columnDefinition = "TEXT")
    private String notes;

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
