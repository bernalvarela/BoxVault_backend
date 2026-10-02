package com.storagemanager.storage_management.model.enums;

/**
 * Qué se hace con un movimiento importado del banco.
 * <ul>
 *   <li>RENT_PAYMENT: es el cobro de una mensualidad de un contrato.</li>
 *   <li>EXPENSE: es un gasto de una unidad (un recibo, un impuesto, una factura).</li>
 *   <li>COMMUNITY_ENTRY: es un apunte del libro de la comunidad de propietarios
 *       (una cuota de un piso, un gasto del edificio).</li>
 *   <li>NONE: no es nada de lo anterior (un traspaso entre cuentas, un
 *       reembolso de suministros) y no genera nada.</li>
 * </ul>
 */
public enum BankLineAction {
    RENT_PAYMENT,
    EXPENSE,
    COMMUNITY_ENTRY,
    NONE
}
