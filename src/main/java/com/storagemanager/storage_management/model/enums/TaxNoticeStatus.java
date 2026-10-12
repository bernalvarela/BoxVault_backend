package com.storagemanager.storage_management.model.enums;

/**
 * En qué punto está una notificación de la AEAT.
 * PENDIENTE: recibida, sin contestar ni pagar; sus plazos corren.
 * RECURRIDA: se presentaron alegaciones o recurso; se espera respuesta.
 * PAGADA: se ingresó lo que pedía.
 * CERRADA: atendida sin pago (un requerimiento contestado, una propuesta archivada)
 *   o anulada.
 */
public enum TaxNoticeStatus {
    PENDIENTE,
    RECURRIDA,
    PAGADA,
    CERRADA
}
