package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.model.enums.TaxModel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Una declaración presentada cuyas cifras ya no coinciden con lo que la
 * aplicación calcula hoy para ese mismo periodo.
 *
 * @param label   "Modelo 303 · 2T 2026", "IRPF 2025 · Bernal Varela"...
 * @param changes cada cifra que se ha movido, con lo presentado y lo de hoy
 */
public record FilingDriftDTO(
        Long filingId,
        TaxModel model,
        Integer year,
        Integer quarter,
        String label,
        LocalDate filedDate,
        List<Change> changes) {

    /** Una cifra: cuánto era al presentar, cuánto es hoy y la diferencia (hoy − presentado). */
    public record Change(String field, BigDecimal filed, BigDecimal current, BigDecimal difference) {}
}
