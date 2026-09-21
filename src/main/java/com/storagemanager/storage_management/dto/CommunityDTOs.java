package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.model.CommunityBudget;
import com.storagemanager.storage_management.model.CommunityEntry;
import com.storagemanager.storage_management.model.OwnersCommunity;
import com.storagemanager.storage_management.model.enums.CommunityEntryType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Lo que la pantalla de la comunidad de propietarios manda y recibe. */
public final class CommunityDTOs {

    private CommunityDTOs() {}

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CommunityDTO {
        private Long id;
        private String name;
        private String taxId;
        private String iban;
        private String notes;
        /** Los portales que administra; más de uno es una mancomunidad. */
        private List<String> buildings;

        public static CommunityDTO of(OwnersCommunity community, List<String> buildings) {
            return CommunityDTO.builder()
                    .id(community.getId())
                    .name(community.getName())
                    .taxId(community.getTaxId())
                    .iban(community.getIban())
                    .notes(community.getNotes())
                    .buildings(buildings)
                    .build();
        }
    }

    @Data
    public static class CommunityRequest {
        @NotBlank(message = "La comunidad necesita un nombre")
        @Size(max = 150)
        private String name;
        @Size(max = 30)
        private String taxId;
        @Size(max = 40)
        private String iban;
        private String notes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BudgetDTO {
        private Long id;
        private Integer year;
        private BigDecimal annualAmount;
        /** Lo mismo al mes, que es como se lee de verdad. */
        private BigDecimal monthlyAmount;
        private String notes;

        public static BudgetDTO of(CommunityBudget budget) {
            return BudgetDTO.builder()
                    .id(budget.getId())
                    .year(budget.getYear())
                    .annualAmount(budget.getAnnualAmount())
                    .monthlyAmount(budget.getAnnualAmount()
                            .divide(new BigDecimal("12"), 2, java.math.RoundingMode.HALF_UP))
                    .notes(budget.getNotes())
                    .build();
        }
    }

    @Data
    public static class BudgetRequest {
        @NotNull(message = "El presupuesto necesita un ejercicio")
        private Integer year;
        @NotNull(message = "El presupuesto necesita un importe")
        private BigDecimal annualAmount;
        private String notes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EntryDTO {
        private Long id;
        private LocalDate entryDate;
        private CommunityEntryType type;
        private String concept;
        private BigDecimal amount;
        /** Positivo si entra, negativo si sale: lo que suma al saldo. */
        private BigDecimal signedAmount;
        private Long storageUnitId;
        private String unitNumber;
        private String supplier;
        private String notes;
        private long documentCount;

        public static EntryDTO of(CommunityEntry entry, long documentCount) {
            BigDecimal signed = entry.getType().isIncome() ? entry.getAmount() : entry.getAmount().negate();
            return EntryDTO.builder()
                    .id(entry.getId())
                    .entryDate(entry.getEntryDate())
                    .type(entry.getType())
                    .concept(entry.getConcept())
                    .amount(entry.getAmount())
                    .signedAmount(signed)
                    .storageUnitId(entry.getStorageUnit() == null ? null : entry.getStorageUnit().getId())
                    .unitNumber(entry.getStorageUnit() == null ? null : entry.getStorageUnit().getUnitNumber())
                    .supplier(entry.getSupplier())
                    .notes(entry.getNotes())
                    .documentCount(documentCount)
                    .build();
        }
    }

    @Data
    public static class EntryRequest {
        @NotNull(message = "El apunte necesita una fecha")
        private LocalDate entryDate;
        @NotNull(message = "El apunte necesita un tipo")
        private CommunityEntryType type;
        @NotBlank(message = "El apunte necesita un concepto")
        @Size(max = 200)
        private String concept;
        @NotNull(message = "El apunte necesita un importe")
        private BigDecimal amount;
        /** Quién paga, en cuotas y derramas; nulo en los gastos del edificio. */
        private Long storageUnitId;
        @Size(max = 150)
        private String supplier;
        private String notes;
    }

    /**
     * El estado de cuentas de un ejercicio: lo que ha entrado, lo que ha salido
     * y qué debe cada unidad.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatementDTO {
        private Long communityId;
        private String communityName;
        private Integer year;
        private BigDecimal budget;
        private BigDecimal income;
        private BigDecimal expenses;
        /** Ingresos menos gastos del ejercicio. */
        private BigDecimal balance;
        /**
         * La suma de los coeficientes de las unidades. Tiene que dar 100; si no,
         * las cuotas están mal calculadas todas a la vez.
         */
        private BigDecimal coefficientTotal;
        private List<UnitStatementDTO> units;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UnitStatementDTO {
        private Long unitId;
        private String unitNumber;
        private String unitName;
        private String buildingName;
        private BigDecimal coefficient;
        /** coeficiente × presupuesto ÷ 12. No se guarda: se calcula. */
        private BigDecimal monthlyQuota;
        /** Lo que debería haber aportado a estas alturas del ejercicio. */
        private BigDecimal expected;
        /** Lo que consta pagado: cuotas y derramas suyas. */
        private BigDecimal paid;
        /** Lo que falta; negativo si va adelantada. */
        private BigDecimal outstanding;
    }
}
