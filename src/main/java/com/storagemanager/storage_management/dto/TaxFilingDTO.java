package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.model.enums.TaxModel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** A filed tax return; {@code snapshot} is the JSON of the report at filing time. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaxFilingDTO {
    private Long id;
    private TaxModel model;
    private Integer year;
    private Integer quarter;
    private Long ownerId;
    private String ownerName;
    private LocalDate filedDate;
    private BigDecimal amount;
    private String description;
    private String snapshot;
    private String notes;
    private LocalDateTime createdAt;

    /** El gasto que acredita el pago (el cargo de la AEAT), cuando se conoce. */
    private Long expenseId;
    /**
     * Lo que la aplicación calcula hoy para ese periodo (la cuota del trimestre en
     * el Modelo 303); null en los modelos que no se recalculan. Sirve para ver si
     * lo que se ingresó sigue cuadrando con los datos.
     */
    private BigDecimal computedAmount;
    /** {@link #amount} − {@link #computedAmount}: positivo, se pagó de más. */
    private BigDecimal difference;
}
