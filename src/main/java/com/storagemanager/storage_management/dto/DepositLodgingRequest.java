package com.storagemanager.storage_management.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Lo que se apunta del depósito de la fianza en el IGVS. Se manda entero cada
 * vez: un campo nulo se borra. Sin fecha de depósito no queda nada de lo demás.
 */
@Data
public class DepositLodgingRequest {

    /** Cuándo se depositó; nulo = no consta depositada. */
    private LocalDate lodgedOn;

    /** Cuánto se depositó; nulo con fecha = la fianza del contrato. */
    private BigDecimal lodgedAmount;

    /** Número de expediente o de justificante del IGVS. */
    private String reference;

    /** Si se entregó al inquilino su copia del justificante. */
    private Boolean receiptDelivered;

    /** Cuándo se pidió la devolución (VI436B). */
    private LocalDate refundRequestedOn;

    /** Cuándo la reintegró el IGVS. */
    private LocalDate refundedOn;
}
