package com.storagemanager.storage_management.model;

import com.storagemanager.storage_management.model.enums.OwnerType;
import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Someone who owns a share of the property: a person or a comunidad de bienes
 * (see {@link OwnerType}). Owners are independent from {@link Client}s (a tenant
 * may also be an owner, but they are separate records). The share each owner
 * holds in a unit is an {@link Ownership}; the members of a comunidad de bienes
 * and their percentages are {@link OwnerMembership}s.
 */
@EntityListeners(AuditingEntityListener.class)
@Entity
@Table(name = "owners")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Owner {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 150)
    private String fullName;

    /** Nullable at the database level for rows created before the column existed; read through {@link #getType()}. */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private OwnerType type;

    @Column(length = 50)
    private String documentId;

    @Column(length = 150)
    private String email;

    @Column(length = 50)
    private String phone;

    /** IBAN where this owner's share of the profits is transferred. */
    @Column(length = 50)
    private String bankAccount;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /** Never null: legacy rows without a type are persons. */
    public OwnerType getType() {
        return type == null ? OwnerType.PERSON : type;
    }

    public boolean isEntity() {
        return getType() == OwnerType.COMUNIDAD_DE_BIENES;
    }

    /** Quién la creó; lo rellena solo AuditingConfig. */
    @CreatedBy
    @Column(updatable = false, length = 60)
    private String createdBy;

    /** Quién la cambió por última vez. */
    @LastModifiedBy
    @Column(length = 60)
    private String updatedBy;
}
