package com.storagemanager.storage_management.model.enums;

/**
 * Categorías de gasto.
 * TRIBUTOS: tasas y tributos locales (IBI, licencias, recaudación municipal).
 * IMPUESTOS: liquidaciones a Hacienda (AEAT).
 * SUMINISTROS: luz, agua, gas...
 * REPARACIONES: arreglos, pintura, mantenimiento.
 * SEGUROS: pólizas de seguro del local.
 * COMUNIDAD: cuotas de la comunidad de propietarios.
 * RESTAURACION_COMPRAS: restauración y compras pagadas con tarjeta.
 * REPARTO_BENEFICIOS: traspasos a los propietarios (reparto de beneficios).
 * INTERESES: intereses del préstamo con el que se compró o se reformó la unidad
 *   (el del 3º E). Solo los intereses: la amortización de capital no es gasto.
 *   Se apuntan una vez al año con el certificado del banco, porque la cuota
 *   mensual del extracto mezcla las dos cosas.
 * OTROS: llaves y cualquier otro gasto.
 */
public enum ExpenseCategory {
    TRIBUTOS,
    IMPUESTOS,
    SUMINISTROS,
    REPARACIONES,
    SEGUROS,
    COMUNIDAD,
    RESTAURACION_COMPRAS,
    REPARTO_BENEFICIOS,
    INTERESES,
    OTROS
}
