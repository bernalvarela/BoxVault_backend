package com.storagemanager.storage_management.model.enums;

/**
 * Qué es una notificación de la AEAT.
 * REQUERIMIENTO: piden que se presente una declaración o que se aporte información.
 * PROPUESTA_SANCION: inicio del expediente sancionador con la propuesta; abre el
 *   plazo de alegaciones.
 * SANCION: el acuerdo que impone la sanción, con su importe y su plazo de pago.
 * RECARGO: recargo por presentar o ingresar fuera de plazo sin requerimiento previo.
 * APREMIO: providencia de apremio, cuando no se pagó en periodo voluntario.
 * OTRO: cualquier otra comunicación.
 */
public enum TaxNoticeKind {
    REQUERIMIENTO,
    PROPUESTA_SANCION,
    SANCION,
    RECARGO,
    APREMIO,
    OTRO
}
