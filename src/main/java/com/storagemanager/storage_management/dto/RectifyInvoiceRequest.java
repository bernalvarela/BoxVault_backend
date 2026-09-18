package com.storagemanager.storage_management.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Lo que hace falta para emitir una rectificativa: la causa.
 * <p>
 * Es obligatoria y no un campo más: el artículo 15 del Reglamento de facturación
 * exige que la factura declare por qué rectifica. "Error en el importe", "el
 * mes se anuló", "el inquilino no era ése".
 */
@Data
public class RectifyInvoiceRequest {

    @NotBlank(message = "Hay que decir la causa de la rectificación")
    @Size(max = 255, message = "La causa no puede pasar de 255 caracteres")
    private String reason;
}
