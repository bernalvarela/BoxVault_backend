package com.storagemanager.storage_management.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * El pago de una notificación. El gasto que lo recoge, una de dos:
 * <ul>
 *   <li>{@code expenseId}: uno que ya existe (el cargo de la AEAT que trajo el
 *       extracto del banco); pasa a la categoría SANCIONES si no lo estaba.</li>
 *   <li>{@code storageUnitId}: se crea ahora, en esa unidad (el local de la
 *       comunidad de bienes), con la categoría SANCIONES.</li>
 * </ul>
 * Sin ninguno de los dos se anota el pago sin gasto.
 */
@Data
public class TaxNoticePaymentRequest {

    @NotNull(message = "Indica la fecha del pago")
    private LocalDate paidOn;

    @NotNull(message = "Indica lo que se pagó")
    @DecimalMin(value = "0.01", message = "Lo pagado tiene que ser mayor que cero")
    private BigDecimal paidAmount;

    private Long expenseId;

    private Long storageUnitId;
}
