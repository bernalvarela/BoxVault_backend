package com.storagemanager.storage_management.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A person's participation in a comunidad de bienes: the percentage of the
 * entity's income (and of its Modelo 184 attribution) that reaches this member.
 */
@Entity
@Table(name = "owner_memberships", uniqueConstraints =
        @UniqueConstraint(name = "uk_membership_entity_member", columnNames = {"entity_id", "member_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OwnerMembership {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The comunidad de bienes. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "entity_id", nullable = false)
    private Owner entity;

    /** The person that is a member of it. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private Owner member;

    /** Percentage of participation, 0 &lt; share &lt;= 100, four decimals. */
    @Column(nullable = false, precision = 7, scale = 4)
    private BigDecimal sharePercent;

    @Column(length = 255)
    private String notes;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
