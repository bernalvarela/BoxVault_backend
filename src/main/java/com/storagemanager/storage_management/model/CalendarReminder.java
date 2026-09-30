package com.storagemanager.storage_management.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Un vencimiento anual que la aplicación no puede deducir de sus datos: el
 * recibo del IBI, la renovación del seguro... Se apunta una vez y el calendario
 * lo repite cada año el mismo día.
 * <p>
 * Los plazos de Hacienda, las bajas avisadas y las fianzas en el IGVS no van
 * aquí: salen solos de los datos, y copiarlos a mano sería tener dos versiones
 * de la misma fecha.
 */
@EntityListeners(AuditingEntityListener.class)
@Entity
@Table(name = "calendar_reminders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CalendarReminder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String title;

    /** Mes del año en que vence, de 1 a 12. */
    @Column(name = "due_month", nullable = false)
    private Integer dueMonth;

    /** Día del mes; en los meses que no lo tienen (un 31 en abril), el último. */
    @Column(name = "due_day", nullable = false)
    private Integer dueDay;

    @Column(length = 255)
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
