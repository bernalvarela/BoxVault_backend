package com.storagemanager.storage_management.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Los datos que pide el formulario del IGVS para depositar una fianza
 * (procedimiento VI436A), sacados del alquiler para copiarlos en la sede de la
 * Xunta sin ir a buscarlos a tres pantallas.
 *
 * Solo existe para los pisos: las fianzas de los trasteros y locales no se
 * depositan en el IGVS.
 *
 * @param landlord         quien deposita: el propietario principal del piso
 * @param owners           todos los propietarios que arriendan, con su NIF
 * @param use              el uso que se declara ("Vivienda")
 * @param monthlyRentBase  la renta sin IVA (en vivienda coincide con la renta)
 * @param deposit          todo lo que entregó el inquilino al firmar
 * @param lodgeAmount      lo que se deposita en el IGVS: la fianza legal, una
 *                         mensualidad (o lo entregado, si fue menos)
 * @param guaranteeAmount  el resto: depósito de garantía adicional, que no se
 *                         deposita y lo guardan los propietarios
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
        BigDecimal lodgeAmount,
        BigDecimal guaranteeAmount,
        LocalDate deadline) {

    /** Una persona o entidad con lo que el formulario pide de ella. */
    public record Person(String name, String taxId, String address) {}
}
