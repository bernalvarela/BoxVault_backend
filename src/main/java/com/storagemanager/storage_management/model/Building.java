package com.storagemanager.storage_management.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Un edificio: el inmueble físico del que cuelga todo lo demás.
 * <p>
 * Hasta ahora la casa era una sola y no hacía falta nombrarla: las unidades
 * raíz -los dos bajos y los cuatro pisos- estaban sueltas en el aire y el árbol
 * empezaba en ellas. Con dos edificios eso deja de valer, y no sólo para
 * ordenar: es el nivel al que se concede el acceso. Dar un edificio a un usuario
 * le da sus unidades de hoy y las que se creen mañana, sin volver a tocar los
 * permisos.
 * <p>
 * No sustituye a la unidad raíz, que sigue siendo "el inmueble" a efectos de
 * gastos, estadísticas y filtros: el edificio es un eje nuevo por encima, no un
 * cambio de significado del que ya había.
 */
@Entity
@Table(name = "buildings",
        uniqueConstraints = @UniqueConstraint(name = "uk_buildings_name", columnNames = "name"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Building {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 200)
    private String address;

    @Column(length = 120)
    private String city;

    /**
     * La comunidad de propietarios que lo administra, si la hay.
     * <p>
     * Va aquí y no al revés porque una comunidad puede llevar varios portales
     * -una mancomunidad- y su NIF y su cuenta son de ella, no de cada edificio:
     * repetirlos en cada portal sería guardar el mismo dato dos veces.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "community_id")
    private OwnersCommunity community;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
