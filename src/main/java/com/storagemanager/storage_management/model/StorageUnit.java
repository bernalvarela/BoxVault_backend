package com.storagemanager.storage_management.model;

import com.storagemanager.storage_management.model.enums.UnitStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "storage_units")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StorageUnit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String unitNumber;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private Double sizeSquareMeters;

    @Column(length = 50)
    private String dimensions;

    @Column(length = 100)
    private String location;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal baseMonthlyRate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private UnitStatus status = UnitStatus.AVAILABLE;

    @Column(columnDefinition = "TEXT")
    private String description;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
