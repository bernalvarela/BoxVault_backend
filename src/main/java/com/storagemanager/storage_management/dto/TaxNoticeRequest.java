package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.model.enums.TaxModel;
import com.storagemanager.storage_management.model.enums.TaxNoticeKind;
import com.storagemanager.storage_management.model.enums.TaxNoticeStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Alta o corrección de una notificación de la AEAT. El pago va aparte ({@link TaxNoticePaymentRequest}). */
@Data
public class TaxNoticeRequest {

    @NotNull(message = "Indica qué tipo de notificación es")
    private TaxNoticeKind kind;

    /** Sin estado, PENDIENTE al crearla y el que tenía al corregirla. */
    private TaxNoticeStatus status;

    private TaxModel model;

    @Min(value = 2000, message = "El ejercicio no puede ser anterior a 2000")
    @Max(value = 2100, message = "El ejercicio no puede ser posterior a 2100")
    private Integer year;

    @Min(value = 1, message = "El trimestre va del 1 al 4")
    @Max(value = 4, message = "El trimestre va del 1 al 4")
    private Integer quarter;

    private Long ownerId;

    /** La declaración presentada de la que trae causa; si no se dice, se busca por modelo, ejercicio y titular. */
    private Long filingId;

    @Size(max = 100, message = "La referencia admite 100 caracteres")
    private String reference;

    @Size(max = 255, message = "La descripción admite 255 caracteres")
    private String description;

    @NotNull(message = "Indica la fecha en la que se recibió")
    private LocalDate receivedOn;

    private LocalDate responseDeadline;
    private LocalDate paymentDeadline;

    @DecimalMin(value = "0.00", message = "El importe no puede ser negativo")
    private BigDecimal amount;

    @DecimalMin(value = "0.00", message = "El importe reducido no puede ser negativo")
    private BigDecimal reducedAmount;

    private String notes;
}
