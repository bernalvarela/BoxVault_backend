package com.storagemanager.storage_management.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Una unidad concedida a un usuario: esa unidad y todo lo que cuelga de ella,
 * ahora y en adelante. Conceder el local BD da sus trasteros de hoy y los que se
 * creen mañana; conceder BD/3 y BD/4 da sólo esos dos (BD se enseña como
 * contexto, para que el árbol se entienda, pero sin sus datos).
 * <p>
 * No hace falta para nada si el usuario tiene {@code fullScope}: entonces ve
 * todas las unidades.
 * <p>
 * La tabla y la entidad existen desde la fase 1, pero quien filtra de verdad por
 * ellas es la fase 2.
 */
@Entity
@Table(name = "user_unit_scopes",
        uniqueConstraints = @UniqueConstraint(name = "uk_user_unit_scopes", columnNames = {"user_id", "storage_unit_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserUnitScope {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    /** La unidad concedida; nula cuando lo concedido es un edificio entero. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "storage_unit_id")
    private StorageUnit storageUnit;

    /**
     * El edificio concedido: sus unidades de hoy y las de mañana. Nulo cuando lo
     * concedido es una unidad suelta. Siempre uno de los dos, nunca los dos.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "building_id")
    private Building building;
}
