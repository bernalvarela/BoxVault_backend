package com.storagemanager.storage_management.model.enums;

/**
 * Qué clase de factura es.
 * <p>
 * ORDINARIA: la factura de la mensualidad, con su número de la serie normal
 *   (A2026/0007).
 * <p>
 * RECTIFICATIVA: la que corrige a otra ya emitida porque lo facturado no se
 *   corresponde con la operación —cambió el importe, el inquilino, o el mes se
 *   anuló—. Lleva número de una serie aparte (R2026/0001), como exige el
 *   artículo 15 del Reglamento de facturación, dice a qué factura rectifica y
 *   por qué, y deja las cifras correctas en sustitución de las anteriores. La
 *   rectificada no se borra ni se cambia: sigue existiendo.
 */
public enum InvoiceType {
    ORDINARIA,
    RECTIFICATIVA
}
