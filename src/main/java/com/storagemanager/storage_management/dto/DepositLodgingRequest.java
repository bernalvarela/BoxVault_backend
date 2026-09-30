package com.storagemanager.storage_management.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Todo lo de la fianza de un contrato, tal como se guarda desde su pestaña:
 * cuánto se entregó y cuánto de eso es garantía, si está cobrada, y su depósito
 * en el IGVS (solo pisos). Se manda entero cada vez: un campo nulo se borra.
 */
@Data
public class DepositLodgingRequest {

    /** Total entregado (fianza más garantía); nulo = no se cambia. */
    private BigDecimal securityDeposit;

    /** Parte del total que es depósito de garantía; nulo = no se ha separado. */
    private BigDecimal guaranteeDeposit;

    /** Si el inquilino la ha pagado; nulo = no se cambia. */
    private Boolean depositPaid;

    /** Cuándo se depositó en el IGVS; nulo = no consta depositada. */
    private LocalDate lodgedOn;

    /** Cuánto se depositó; nulo con fecha = la fianza legal. */
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
