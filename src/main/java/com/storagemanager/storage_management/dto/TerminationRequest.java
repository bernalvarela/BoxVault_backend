package com.storagemanager.storage_management.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Lo que se decide al cerrar un contrato: el día de salida, qué pasa con la
 * fianza y si se genera el contrato de salida.
 * <p>
 * Es el mismo cuerpo para la vista previa del contrato de salida y para el
 * cierre de verdad: así lo que se firma es exactamente lo que se leyó.
 */
@Data
public class TerminationRequest {

    /** Día de salida; nulo = hoy. */
    private LocalDate terminationDate;

    /**
     * Si se devuelve la fianza (toda o parte). Nulo cuando el contrato no tenía
     * fianza: no hay nada que decidir.
     */
    private Boolean depositReturned;

    /** Cuánto se devuelve; nulo con {@code depositReturned} = la fianza entera. */
    private BigDecimal depositReturnedAmount;

    /** Por qué no se devuelve entera. */
    private String depositReturnNotes;

    /** Si se compone y archiva el contrato de salida al cerrar. */
    private Boolean generateExitContract;
}
