package com.storagemanager.storage_management.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * La comunidad de propietarios de un edificio: los vecinos organizados.
 * <p>
 * No confundir con la comunidad de BIENES ({@code OwnerType.COMUNIDAD_DE_BIENES}),
 * que es una envoltura fiscal de unos señores que sí poseen unidades y atribuye
 * su renta en el modelo 184. Esta otra no posee nada ni tributa: recauda cuotas
 * de sus propietarios y paga los gastos del edificio.
 * <p>
 * Por eso es una entidad aparte y no un tipo más de {@link Owner}: si fuese un
 * propietario, {@code InvoiceIssuer} la elegiría como emisora de las facturas
 * -prefiere las entidades- y acabaría encabezando contratos de unidades que no
 * son suyas.
 * <p>
 * Un edificio apunta a la suya; varios pueden apuntar a la misma, que es lo que
 * en la calle se llama una mancomunidad.
 */
@Entity
@Table(name = "owners_communities")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OwnersCommunity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    /** NIF propio: una comunidad de propietarios lo tiene, y factura a su nombre. */
    @Column(length = 30)
    private String taxId;

    /** La cuenta donde se ingresan las cuotas. */
    @Column(length = 40)
    private String iban;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
