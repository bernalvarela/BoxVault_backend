package com.storagemanager.storage_management.model.enums;

/**
 * A qué viene cada persona que firma un contrato.
 * <p>
 * El arrendador no está aquí: ése no se elige, sale de quién es dueño de la
 * unidad (la pantalla de Propietarios). Esto es la otra parte de la mesa.
 */
public enum PartyRole {

    /**
     * Alquila. Puede haber varios y responden todos: el primero de la lista es
     * el titular, el que lleva los cobros y las facturas.
     */
    ARRENDATARIO,

    /**
     * Avala a los arrendatarios. No alquila nada -no se le generan
     * mensualidades ni aparece como titular-, sólo responde si no pagan.
     */
    FIADOR
}
