package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.model.enums.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Corrección a mano de la mensualidad de un contrato: lo que se le hace decir a
 * un mes concreto cuando lo deducido de los contratos no cuadra con la realidad
 * (una renta distinta ese mes, un cobro registrado a medias, o un mes que
 * sencillamente no se le va a cobrar a nadie).
 * <p>
 * A diferencia de {@link RecordPaymentRequest}, que sólo toca el dinero recibido,
 * aquí se fija también lo que se debe, y el importe puede ser cero: con
 * {@code waived} el mes queda marcado como no cobrable.
 */
@Data
public class ChargeAdjustmentRequest {

    @NotNull(message = "Amount due is required")
    @DecimalMin(value = "0.00", message = "Amount due cannot be negative")
    private BigDecimal amountDue;

    @DecimalMin(value = "0.00", message = "Amount paid cannot be negative")
    private BigDecimal amountPaid = BigDecimal.ZERO;

    /** Cuándo entró el dinero; sobra si no se ha cobrado nada. */
    private LocalDate paymentDate;

    /** El día de cobro pactado del mes; si falta se conserva el que ya tenía. */
    private LocalDate dueDate;

    private PaymentMethod paymentMethod;

    private String transactionReference;

    private String notes;

    /** El mes no se le cobra a nadie: ni se espera, ni vence. */
    private boolean waived;
}
