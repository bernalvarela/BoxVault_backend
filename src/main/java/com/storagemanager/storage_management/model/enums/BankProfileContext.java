package com.storagemanager.storage_management.model.enums;

/**
 * De quién es la cuenta de un extracto, y por tanto qué se propone de entrada
 * con sus movimientos: PROPIETARIOS (cobros de alquiler y gastos de las
 * unidades) o COMUNIDAD (cuotas y gastos del libro de la comunidad de
 * propietarios). Cada fila se puede cambiar después en la revisión.
 */
public enum BankProfileContext {
    PROPIETARIOS,
    COMUNIDAD
}
