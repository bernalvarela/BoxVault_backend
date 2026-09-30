package com.storagemanager.storage_management.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Los datos que pide el formulario del IGVS para depositar una fianza
 * (procedimiento VI436A), sacados del alquiler para copiarlos en la sede de la
 * Xunta sin ir a buscarlos a tres pantallas.
 *
 * @param landlord         quien deposita: el emisor de la unidad (la comunidad
 *                         de bienes en los trasteros, el propietario en un piso)
 * @param owners           todos los propietarios que arriendan, con su NIF
 * @param use              "Vivienda" o "Uso distinto de vivienda"; de ello
 *                         depende cuántas mensualidades son de fianza
 * @param monthlyRentBase  la renta sin IVA: la fianza de uso distinto son dos
 *                         mensualidades SIN IVA
 * @param expectedDeposit  lo que debería ser la fianza según la ley (una o dos
 *                         mensualidades), para ver si la del contrato cuadra
 * @param deadline         hasta cuándo hay para depositarla: un mes desde el
 *                         inicio del contrato
 */
public record DepositFormDTO(
        Person landlord,
        List<Person> owners,
        List<Person> tenants,
        String unitName,
        String unitAddress,
        String cadastralReference,
        String use,
        String agreementNumber,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal monthlyRent,
        BigDecimal monthlyRentBase,
        BigDecimal deposit,
        int expectedMonths,
        BigDecimal expectedDeposit,
        LocalDate deadline) {

    /** Una persona o entidad con lo que el formulario pide de ella. */
    public record Person(String name, String taxId, String address) {}
}
