package com.storagemanager.storage_management.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storagemanager.storage_management.model.enums.UnitKind;
import com.storagemanager.storage_management.model.enums.UnitStatus;
import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A unit: a storage unit ("trastero"), an apartment or a local (business
 * premises). Units form a tree through {@link #parent}: the trasteros sit inside
 * the "Bajo delantero" local. The topmost unit of the chain ({@link #getRoot()})
 * is what the statistics filter by, and a unit without shares of its own inherits
 * the owners of its parent.
 */
@EntityListeners(AuditingEntityListener.class)
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

    /**
     * Storage unit, apartment or local. Nullable at the database level so rows created
     * before the column existed survive the schema update; a null is read as
     * {@link UnitKind#STORAGE_UNIT}.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    @Builder.Default
    private UnitKind kind = UnitKind.STORAGE_UNIT;

    /** The unit this one sits inside (a trastero inside a local); null for a top-level unit. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "parent_unit_id")
    private StorageUnit parent;

    /**
     * El edificio en el que está. Sólo lo llevan las unidades raíz: las demás lo
     * heredan subiendo, y guardárselo a cada trastero sería repetir un dato que
     * ya está en su local. Se lee con {@link #building()}.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "building_id")
    private Building building;

    /**
     * El coeficiente de participación de la escritura, en tanto por ciento.
     * <p>
     * Es de la finca registral y no de quien la posee hoy: si se vende el 3D, el
     * coeficiente se va con el piso. Sólo lo tienen los elementos de la
     * propiedad horizontal -los bajos y los pisos-; los trasteros son divisiones
     * dentro del bajo y no participan por separado.
     */
    @Column(precision = 7, scale = 4)
    private java.math.BigDecimal participationCoefficient;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private Double sizeSquareMeters;

    @Column(length = 50)
    private String dimensions;

    @Column(length = 100)
    private String location;

    /** Referencia catastral of the property (needed on the tax returns). */
    @Column(length = 30)
    private String cadastralReference;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal baseMonthlyRate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private UnitStatus status = UnitStatus.AVAILABLE;

    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * Los muebles y enseres que hay dentro, uno por línea. Es del piso y no del
     * contrato: el sofá sigue ahí cuando cambia el inquilino. Sale en el contrato
     * a través de {@code {{inventario}}}.
     */
    @Column(columnDefinition = "TEXT")
    private String inventory;

    /**
     * Con qué plantilla se componen los contratos de esta unidad, salvo que el
     * contrato diga otra cosa. Nulo = la del local que la contiene, y si ninguno
     * dice nada, la marcada por defecto.
     * <p>
     * Es lo que evita tener que acordarse en cada alta: los pisos se alquilan con
     * el contrato de vivienda y los trasteros con el suyo, y eso no cambia de un
     * inquilino a otro. Se hereda del padre igual que los propietarios, así que
     * basta con marcarla en el local para que la lleven sus nueve trasteros.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "contract_template_id")
    private ContractTemplate contractTemplate;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /** Never null: legacy rows without a kind are storage units. */
    public UnitKind getKind() {
        return kind == null ? UnitKind.STORAGE_UNIT : kind;
    }

    /** Whether this unit's prices carry 21% VAT (storage units, locales) or are exempt (apartments). Serialised as {@code vatApplicable}. */
    @JsonProperty("vatApplicable")
    public boolean isVatApplicable() {
        return getKind().isVatApplicable();
    }

    /**
     * Un local, con o sin unidades dentro. Serializado como {@code premises}.
     * <p>
     * No dice si se alquila o no: eso depende de si algo cuelga de él, y una
     * unidad suelta no lo sabe. Un local con trasteros dentro (el bajo
     * delantero) no se alquila entero; uno vacío (el bajo trasero) se alquila
     * como cualquier otra unidad. Quien necesite esa distinción tiene que mirar
     * el inventario: {@code Units.containerIds(...)}.
     */
    @JsonProperty("premises")
    public boolean isPremises() {
        return getKind() == UnitKind.PREMISES;
    }

    /** El edificio de esta unidad: el suyo, o el de la raíz de la que cuelga. */
    public Building building() {
        return building != null ? building : rootUnit().getBuilding();
    }

    /** Topmost unit of the parent chain (this unit when it has no parent). */
    public StorageUnit rootUnit() {
        StorageUnit u = this;
        int guard = 0;
        while (u.getParent() != null && guard++ < 32) {
            u = u.getParent();
        }
        return u;
    }

    /** Id of the root unit; what statistics and listings filter by. */
    @JsonProperty("rootId")
    public Long getRootId() {
        return rootUnit().getId();
    }

    /** Small reference to the root unit, serialised as {@code root}. */
    @JsonProperty("root")
    public UnitRef getRoot() {
        return UnitRef.of(rootUnit());
    }

    /** Lightweight reference to a unit for JSON payloads. */
    public record UnitRef(Long id, String unitNumber, String name, UnitKind kind) {
        public static UnitRef of(StorageUnit u) {
            return u == null ? null : new UnitRef(u.getId(), u.getUnitNumber(), u.getName(), u.getKind());
        }
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
