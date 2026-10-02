package com.storagemanager.storage_management.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Un extracto subido: de qué cuenta (su perfil), qué fichero y sus movimientos.
 * <p>
 * REVIEW mientras queda algo por decidir; APPLIED cuando ya no queda ningún
 * movimiento pendiente.
 */
@EntityListeners(AuditingEntityListener.class)
@Entity
@Table(name = "bank_imports")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankImport {

    public static final String REVIEW = "REVIEW";
    public static final String APPLIED = "APPLIED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "profile_id", nullable = false)
    private BankImportProfile profile;

    @Column(length = 255)
    private String fileName;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = REVIEW;

    @OneToMany(mappedBy = "bankImport", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNumber ASC")
    @Builder.Default
    private List<BankImportLine> lines = new ArrayList<>();

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @CreatedBy
    @Column(updatable = false, length = 60)
    private String createdBy;
}
