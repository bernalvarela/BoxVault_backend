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
    OTROS
}
