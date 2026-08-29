package com.storagemanager.storage_management.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Modelo 184 (yearly informative return of an entity in "atribución de rentas"):
 * the rent a comunidad de bienes collected in a year through the units it holds
 * a share of, and how it is attributed to each member by their membership
 * percentage. Amounts are the mensualidades PAID whose billing period falls in
 * the year; the base excludes VAT.
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

    /** The entity's part of the income of its units. */
    private BigDecimal incomeBase;
    private BigDecimal incomeVat;
    private BigDecimal incomeTotal;
    /** Part of the income attributed to the members (their percentages may not add up to 100 %). */
    private BigDecimal attributedBase;
    private BigDecimal unattributedBase;
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
    }
}
