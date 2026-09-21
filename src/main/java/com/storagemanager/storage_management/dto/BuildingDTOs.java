package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.model.Building;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Lo que la pantalla de edificios manda y recibe. */
public final class BuildingDTOs {

    private BuildingDTOs() {}

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BuildingDTO {
        private Long id;
        private String name;
        private String address;
        private String city;
        private String notes;

        /** La comunidad de propietarios que lo administra, si la hay. */
        private Long communityId;
        private String communityName;

        /** Cuántas unidades raíz cuelgan de él. */
        private long unitCount;

        /**
         * La suma de los coeficientes de sus unidades.
         * <p>
         * Tiene que dar 100. Es el detector de errores clásico de las
         * comunidades: delata al instante un dato mal copiado de la escritura,
         * que si no se nota descuadra todas las cuotas a la vez y en silencio.
         */
        private BigDecimal coefficientTotal;

        public static BuildingDTO of(Building building, long unitCount, BigDecimal coefficientTotal) {
            return BuildingDTO.builder()
                    .id(building.getId())
                    .name(building.getName())
                    .address(building.getAddress())
                    .city(building.getCity())
                    .notes(building.getNotes())
                    .communityId(building.getCommunity() == null ? null : building.getCommunity().getId())
                    .communityName(building.getCommunity() == null ? null : building.getCommunity().getName())
                    .unitCount(unitCount)
                    .coefficientTotal(coefficientTotal)
                    .build();
        }
    }

    @Data
    public static class BuildingRequest {
        @NotBlank(message = "El edificio necesita un nombre")
        @Size(max = 120, message = "El nombre no puede pasar de 120 caracteres")
        private String name;

        @Size(max = 200)
        private String address;

        @Size(max = 120)
        private String city;

        /** La comunidad que lo administra; nulo = ninguna. */
        private Long communityId;

        private String notes;
    }
}
