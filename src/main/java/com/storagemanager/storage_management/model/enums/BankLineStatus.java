package com.storagemanager.storage_management.model.enums;

/**
 * En qué punto está un movimiento importado: PENDING (se aplicará al
 * confirmar), DISCARDED (no interesa, o es un duplicado), APPLIED (ya creó su
 * cobro, gasto o apunte) o FAILED (se intentó aplicar y falló; se puede
 * corregir y volver a aplicar).
 */
public enum BankLineStatus {
    PENDING,
    DISCARDED,
    APPLIED,
    FAILED
}
