package com.storagemanager.storage_management.model;

import com.storagemanager.storage_management.model.enums.SigningKind;
import com.storagemanager.storage_management.model.enums.SigningStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * La firma electrónica de un contrato, de entrada o de salida.
 * <p>
 * Al prepararla se compone el contrato una última vez y ese PDF queda
 * congelado ({@link #originalDocument}): lo que firma cada uno es exactamente
 * ese fichero, cuya huella se guarda en {@link #originalSha256}. Mientras está
 * en curso, el contrato no se puede rehacer, unir ni borrar; si hay que
 * cambiar algo, se cancela y se prepara otra.
 * <p>
 * Cuando han firmado todos, las firmas se estampan sobre ese PDF, se añade la
 * hoja de evidencias y se sella: el resultado es {@link #signedDocument}.
 */
@EntityListeners(AuditingEntityListener.class)
@Entity
@Table(name = "contract_signings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContractSigning {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "rental_agreement_id", nullable = false)
    private RentalAgreement rentalAgreement;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SigningKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    @Builder.Default
    private SigningStatus status = SigningStatus.EN_CURSO;

    /** El PDF tal como se firma. No se toca ni se borra mientras exista la firma. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "original_document_id", nullable = false)
    private Document originalDocument;

    /** SHA-256 de {@link #originalDocument}, en hexadecimal. */
    @Column(name = "original_sha256", nullable = false, length = 64)
    private String originalSha256;

    /** Dónde quedó cada hueco de firma en el PDF; ver {@code SigningSlots}. */
    @Column(columnDefinition = "TEXT")
    private String slots;

    /** El PDF firmado por todos y sellado. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "signed_document_id")
    private Document signedDocument;

    /**
     * Quién la preparó. Lo que se archiva después, aunque lo dispare la firma de
     * un inquilino desde su móvil, queda a su nombre y con su ámbito.
     */
    @Column(name = "prepared_by_user_id", nullable = false)
    private Long preparedByUserId;

    // Con su propia consulta y no en el mismo join: el contrato ya trae sus
    // partes como lista, y Hibernate no carga dos listas de golpe en una sola
    // consulta (MultipleBagFetchException).
    @OneToMany(mappedBy = "signing", fetch = FetchType.EAGER, cascade = CascadeType.ALL, orphanRemoval = true)
    @Fetch(FetchMode.SELECT)
    @OrderBy("position ASC")
    @Builder.Default
    private List<ContractSigner> signers = new ArrayList<>();

    private Instant completedAt;

    private Instant cancelledAt;

    @Column(length = 255)
    private String cancelReason;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @CreatedBy
    @Column(length = 60, updatable = false)
    private String createdBy;

    @LastModifiedBy
    @Column(length = 60)
    private String updatedBy;

    public boolean isInProgress() {
        return status == SigningStatus.EN_CURSO;
    }

    /** Si ya han firmado todos (aunque falte sellar). */
    public boolean allSigned() {
        return !signers.isEmpty() && signers.stream().allMatch(ContractSigner::hasSigned);
    }
}
