package com.storagemanager.storage_management.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Modelo 184 (yearly informative return of an entity in "atribución de rentas"):
 * the rent a comunidad de bienes collected in a year through the units it holds
 * a share of, its deductible expenses, and how the resulting net yield is
 * attributed to each member by their membership percentage. A comunidad de bienes
 * does not pay tax itself: it works out the yield as a person would -income minus
 * deductible expenses- and attributes it; its members declare it in their IRPF.
 * Income is the mensualidades PAID whose billing period falls in the year, with
 * the base excluding VAT; expenses exclude the input VAT deducted in the 303.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Modelo184DTO {

    private int year;
    /** The comunidad de bienes; null (with {@code message}) when none exists. */
    private Long entityId;
    private String entityName;
    private String message;
    private long unitCount;

    /** The entity's part of the income of its units (ingresos íntegros). */
    private BigDecimal incomeBase;
    private BigDecimal incomeVat;
    private BigDecimal incomeTotal;
    /** Gastos deducibles de la entidad, ya netos del IVA soportado que se deduce en el 303. */
    private BigDecimal expenses;
    private Map<String, BigDecimal> expensesByCategory;
    /**
     * Rendimiento neto ({@code incomeBase - expenses}): lo que de verdad se
     * atribuye a los miembros, porque la comunidad no tributa por sí misma.
     */
    private BigDecimal netBase;
    /** Part of the income attributed to the members (their percentages may not add up to 100 %). */
    private BigDecimal attributedBase;
    private BigDecimal unattributedBase;
    /** La parte del rendimiento neto que recogen los miembros. */
    private BigDecimal attributedNet;
    private BigDecimal membersSharePercent;

    private List<Member> members;
    private List<UnitShare> units;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Member {
        private Long ownerId;
        private String ownerName;
        /** Membership percentage in the entity. */
        private BigDecimal sharePercent;
        private BigDecimal incomeBase;
        private BigDecimal incomeVat;
        private BigDecimal incomeTotal;
        /** Su parte de los gastos deducibles de la entidad. */
        private BigDecimal expenses;
        /** Lo que declara en su IRPF: su parte del rendimiento neto. */
        private BigDecimal net;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UnitShare {
        private Long unitId;
        private String unitNumber;
        private String unitName;
        private String parentUnitNumber;
        /** The entity's share of the unit. */
        private BigDecimal sharePercent;
        private boolean inherited;
        /** Income of the whole unit (100 %), base without VAT. */
        private BigDecimal unitIncomeBase;
        /** The entity's part of it. */
        private BigDecimal incomeBase;
        private BigDecimal incomeVat;
        private BigDecimal incomeTotal;
        /** Gastos deducibles de la unidad (100 %) y la parte que le toca a la entidad. */
        private BigDecimal unitExpenses;
        private BigDecimal expenses;
        private BigDecimal net;
    }
}
