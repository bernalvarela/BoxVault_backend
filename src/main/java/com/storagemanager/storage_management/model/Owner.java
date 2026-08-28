package com.storagemanager.storage_management.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * A person (or company) that owns a share of the rented property: one or more
 * storage groups or individual units. Owners are independent from
 * {@link Client}s (a tenant may also be an owner, but they are separate records).
 * The share each owner holds in a group or unit is an {@link Ownership}.
 */
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
}
