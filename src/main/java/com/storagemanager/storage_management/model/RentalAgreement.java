package com.storagemanager.storage_management.model;

import com.storagemanager.storage_management.model.enums.PartyRole;
import com.storagemanager.storage_management.model.enums.RentalStatus;
import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@EntityListeners(AuditingEntityListener.class)
@Entity
@Table(name = "rental_agreements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RentalAgreement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String agreementNumber;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "storage_unit_id", nullable = false)
    private StorageUnit storageUnit;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    /**
     * Second tenant of the same contract (co-titular), if any.
     * <p>
     * Ya no se elige: es un reflejo de {@link #parties}, el segundo
     * ARRENDATARIO de la lista. Sigue existiendo porque de él cuelgan el modelo
     * 184, el IRPF, el historial de la unidad y el control de acceso, que
     * declaran al inquilino por su NIF.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "co_client_id")
    private Client coClient;

    /**
     * Quién firma este contrato y en calidad de qué: los arrendatarios, que
     * pueden ser los que hagan falta, y los fiadores.
     * <p>
     * Ésta es la lista de verdad. {@link #client}, {@link #coClient} y
     * {@link #guarantor} son su reflejo -el primer arrendatario, el segundo y el
     * primer fiador- y los mantiene al día RentalAgreementService en cada
     * guardado: así lo que ya leía esas columnas (cobros, facturas, impuestos,
     * ámbito de acceso) sigue funcionando sin enterarse de nada.
     */
    @OneToMany(mappedBy = "rentalAgreement", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.EAGER)
    @OrderBy("position ASC, id ASC")
    @Builder.Default
    private List<RentalParty> parties = new ArrayList<>();

    /**
     * Quien avala a los inquilinos, si el contrato lleva fiador solidario.
     * <p>
     * Es una ficha de cliente como las demás -tiene nombre, NIF y teléfono, y
     * suele repetirse entre contratos- pero no alquila nada: no se le generan
     * mensualidades ni aparece como titular. Sólo responde si los inquilinos no
     * pagan, y por eso sale en su cláusula del contrato.
     * <p>
     * Como {@link #coClient}, es un reflejo de {@link #parties}: el primer
     * FIADOR. Si el contrato lleva dos, el segundo sólo está en la lista.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "guarantor_id")
    private Client guarantor;

    @Column(nullable = false)
    private LocalDate startDate;

    private LocalDate endDate;

    @Column(nullable = false)
    @Builder.Default
    private Integer billingDayOfMonth = 1;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal monthlyRent;

    @Column(precision = 10, scale = 2)
    private BigDecimal securityDeposit;

    /**
     * Gastos que este inquilino asume aparte de la renta: la cuota de comunidad
     * (al mes) y el IBI (al año).
     * <p>
     * Van en el contrato y no en la unidad porque son una cláusula, no un hecho
     * del piso: el mismo piso puede alquilarse con los gastos incluidos o con
     * ellos aparte, y eso se pacta con cada inquilino. Nulo = no se pactó nada y
     * el contrato no dice nada de ellos.
     */
    @Column(precision = 10, scale = 2)
    private BigDecimal communityFee;

    @Column(precision = 10, scale = 2)
    private BigDecimal propertyTax;

    @Builder.Default
    private Boolean depositPaid = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private RentalStatus status = RentalStatus.ACTIVE;

    @Builder.Default
    private Boolean autoRenew = true;

    /**
     * Si de este contrato se emiten facturas: al registrar un cobro sale sola la
     * factura de esa mensualidad, con su número de serie.
     * <p>
     * Se decide por contrato y no por unidad porque no depende del trastero sino
     * de quién lo alquila: una empresa necesita la factura para deducirse el IVA
     * y un particular no la pide nunca. Por defecto no: una factura emitida
     * consume un número de la serie y ya no se puede deshacer, así que se marca
     * a conciencia. En un alquiler de vivienda no se puede marcar: está exento.
     */
    @Builder.Default
    private Boolean generatesInvoices = false;

    /**
     * Con qué plantilla se compone su contrato en PDF. Nulo = la que esté marcada
     * por defecto, que es lo normal; se elige otra cuando ese alquiler necesita
     * un texto distinto (un local, un trastero sin fianza...).
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "contract_template_id")
    private ContractTemplate contractTemplate;

    /**
     * El contrato en PDF que generó la aplicación desde la plantilla, si ya se
     * generó alguno.
     * <p>
     * Se guarda la referencia para poder REHACERLO: si el inquilino pide un
     * cambio, se compone otra vez y el borrador anterior se borra, en vez de ir
     * dejando contratos apilados entre los documentos del alquiler sin saber
     * cuál es el bueno. Sólo apunta al que hizo la aplicación: la copia firmada
     * que se sube a mano es otro documento y no se toca nunca.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "contract_document_id")
    private Document contractDocument;

    /** Nunca null: un contrato sin marcar no factura. */
    public boolean invoices() {
        return Boolean.TRUE.equals(generatesInvoices);
    }

    /** Los arrendatarios, en orden; el primero es el titular. */
    public List<Client> tenants() {
        return of(PartyRole.ARRENDATARIO);
    }

    /** Los fiadores, en orden. */
    public List<Client> guarantors() {
        return of(PartyRole.FIADOR);
    }

    /**
     * Las personas de un papel, en el orden de la lista.
     * <p>
     * Con la lista vacía cae a las columnas de siempre. Eso no es un apaño
     * temporal: un contrato recién construido en memoria -el de la vista previa
     * de una plantilla, por ejemplo- no ha pasado por la base y no tiene
     * partes, y tiene que poder imprimirse igual.
     */
    private List<Client> of(PartyRole role) {
        if (parties != null && !parties.isEmpty()) {
            return parties.stream()
                    .filter(party -> party.getRole() == role)
                    .map(RentalParty::getClient)
                    .filter(java.util.Objects::nonNull)
                    .toList();
        }
        List<Client> fallback = role == PartyRole.ARRENDATARIO
                ? java.util.Arrays.asList(client, coClient)
                : java.util.Collections.singletonList(guarantor);
        return fallback.stream().filter(java.util.Objects::nonNull).toList();
    }

    @Column(columnDefinition = "TEXT")
    private String notes;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /** Quién la creó; lo rellena solo AuditingConfig. */
    @CreatedBy
    @Column(updatable = false, length = 60)
    private String createdBy;

    /** Quién la cambió por última vez. */
    @LastModifiedBy
    @Column(length = 60)
    private String updatedBy;
}
