package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.service.Modelo303FileService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Presentar el Modelo 303 de un trimestre: lo que hay que decidir al hacerlo.
 * Las cifras no viajan, se calculan en el servidor con los datos del momento;
 * aquí sólo va lo que no se puede deducir de ellos.
 */
@Data
public class Modelo303PresentationRequest {

    @NotNull(message = "Year is required")
    @Min(value = 2000, message = "Year must be 2000 or later")
    @Max(value = 2100, message = "Year must be 2100 or earlier")
    private Integer year;

    @NotNull(message = "Quarter is required")
    @Min(value = 1, message = "Quarter must be between 1 and 4")
    @Max(value = 4, message = "Quarter must be between 1 and 4")
    private Integer quarter;

    /** La comunidad de bienes que presenta; si no se dice, la única que hay. */
    private Long ownerId;

    /** Fecha de presentación; hoy si no se dice. */
    private LocalDate filedDate;

    /** Sobre qué se declara: lo cobrado (por defecto) o todo lo devengado. */
    private Modelo303FileService.Basis basis;

    /** [110] cuotas a compensar pendientes de periodos anteriores. */
    private BigDecimal pendingToOffset;

    /** [78] las que se aplican en este periodo. */
    private BigDecimal offsetApplied;

    /** Domiciliar el ingreso en la cuenta de la comunidad. */
    private boolean directDebit;

    private String notes;
}
