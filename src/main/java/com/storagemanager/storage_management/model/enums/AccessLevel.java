package com.storagemanager.storage_management.model.enums;

/**
 * Cuánto puede hacer un usuario en un área. Son acumulativos y van de menos a
 * más, así que comparar por orden es suficiente: quien tiene ESCRIBIR también
 * puede leer, y quien tiene ADMINISTRAR también puede escribir.
 * <p>
 * Borrar va aparte de escribir a propósito: corregir una errata en un contrato y
 * borrar el contrato entero no son la misma responsabilidad.
 */
public enum AccessLevel {
    /** Ni ve el menú. */
    NINGUNO,
    /** Consultar y descargar; nada que cambie datos. */
    LEER,
    /** Lo anterior, más crear y modificar. */
    ESCRIBIR,
    /** Lo anterior, más borrar. */
    ADMINISTRAR;

    /** Si este nivel llega a lo que se pide. */
    public boolean allows(AccessLevel required) {
        return ordinal() >= required.ordinal();
    }
}
