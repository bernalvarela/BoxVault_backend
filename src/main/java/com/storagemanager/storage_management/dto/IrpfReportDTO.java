package com.storagemanager.storage_management.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * IRPF (yearly income tax) helper for the persons. For every person, two blocks:
 * <ul>
 *   <li><b>rental</b> — "Rendimiento de alquileres": the units they hold a share
 *       of directly (the flats): their part of the rent collected and of the
 *       deductible expenses (comunidad, seguros, IBI...), and the net yield;</li>
 *   <li><b>attribution</b> — "Ganancias en régimen de atribución de rentas":
 *       their membership percentage of each comunidad de bienes' income, i.e. the
 *       same figure the entity's Modelo 184 attributes to them.</li>
 * </ul>
 * Income = mensualidades PAID whose billing period falls in the year, base
 * without VAT. Expenses = those dated in the year, on the unit itself, whose
 * category is deductible ({@code deductibleCategories}). Expenses of units held
 * by an entity belong to the entity and are left out ({@code entityExpenses}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IrpfReportDTO {

    private int year;
    private List<String> entityNames;
    private List<String> deductibleCategories;

    private List<OwnerReport> owners;

    // Whole-portfolio figures for the persons
    private BigDecimal totalRentalIncomeBase;
    private BigDecimal totalRentalExpenses;
    private BigDecimal totalRentalNet;
    private BigDecimal totalAttributionIncomeBase;

    // What could not be attributed to any owner, and what belongs to the entities
    private BigDecimal unattributedIncomeBase;
    private BigDecimal unattributedExpenses;
    private List<String> unitsWithoutOwners;
    private BigDecimal entityExpenses;

    /** Expenses of the year left out because their category is not deductible, by category. */
    private Map<String, BigDecimal> excludedExpensesByCategory;
    private BigDecimal excludedExpenses;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OwnerReport {
        private Long ownerId;
        private String ownerName;

        /** Rendimiento de alquileres (directly-held units). */
        private Section rental;
        /** Ganancias en régimen de atribución de rentas (same amount as the entities' Modelo 184). */
        private Section attribution;

        /** rental.net + attribution.incomeBase. */
        private BigDecimal totalNet;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Section {
        private List<Line> lines;
        private BigDecimal incomeBase;
        private BigDecimal incomeVat;
        private BigDecimal incomeTotal;
        private Map<String, BigDecimal> expensesByCategory;
        private BigDecimal expenses;
        private BigDecimal net;
    }

    /** One property (or entity) of an owner with the owner's share of its income and expenses. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Line {
        /** "UNIT" (a unit held directly) or "ENTITY" (a comunidad de bienes the person belongs to). */
        private String scope;
        private Long unitId;
        private String unitNumber;
        private String unitName;
        private String kind;
        private boolean vatApplicable;
        private Long parentUnitId;
        private String parentUnitNumber;
        private String parentUnitName;
        private Long entityId;
        private String entityName;

        private BigDecimal sharePercent;
        private boolean inherited;

        /** Income of the whole unit / entity (100 %). */
        private BigDecimal unitIncomeBase;
        private BigDecimal unitIncomeTotal;
        /** The owner's part. */
        private BigDecimal incomeBase;
        private BigDecimal incomeVat;
        private BigDecimal incomeTotal;

        private List<Rental> rentals;

        /** The owner's part of the deductible expenses of the property, by category (empty in the attribution block). */
        private Map<String, BigDecimal> expensesByCategory;
        /** Deductible expenses of the whole property (100 %). */
        private BigDecimal unitExpenses;
        private BigDecimal expenses;
        private BigDecimal net;
    }

    /** A tenancy of the unit that produced income in the year (amounts of the whole unit, not the owner's share). */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Rental {
        private Long rentalId;
        private String agreementNumber;
        private String clientName;
        private LocalDate startDate;
        private LocalDate endDate;
        private String status;
        private long paidMonths;
        private BigDecimal incomeTotal;
        private BigDecimal incomeBase;
        private BigDecimal incomeVat;
    }
}
