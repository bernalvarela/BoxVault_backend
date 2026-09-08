package com.storagemanager.storage_management.controller;

import com.storagemanager.storage_management.dto.IrpfReportDTO;
import com.storagemanager.storage_management.dto.Modelo184DTO;
import com.storagemanager.storage_management.dto.Modelo303DTO;
import com.storagemanager.storage_management.dto.TaxFilingDTO;
import com.storagemanager.storage_management.dto.TaxFilingRequest;
import com.storagemanager.storage_management.model.enums.TaxModel;
import com.storagemanager.storage_management.service.TaxFilingService;
import com.storagemanager.storage_management.service.TaxService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Year;
import java.util.List;

/**
 * Tax helper reports and the register of filed returns. {@code year} defaults to
 * the current year; {@code ownerId} scopes the 303 / 184 to one owner (the
 * comunidad de bienes that files them).
 */
@RestController
@RequestMapping("/api/taxes")
@RequiredArgsConstructor
public class TaxController {

    private final TaxService taxService;
    private final TaxFilingService taxFilingService;

    private static int yearOrCurrent(Integer year) {
        return year != null ? year : Year.now().getValue();
    }

    /** Modelo 303: quarterly VAT of the VAT-bearing units, optionally only those held by an owner (the comunidad de bienes). */
    @PreAuthorize("@access.can('IMPUESTOS','LEER')")
    @GetMapping("/modelo-303")
    public ResponseEntity<Modelo303DTO> modelo303(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Long ownerId) {
        return ResponseEntity.ok(taxService.modelo303(yearOrCurrent(year), ownerId));
    }

    /** Modelo 184: yearly income of a comunidad de bienes (default: the first one) attributed to its members. */
    @PreAuthorize("@access.can('IMPUESTOS','LEER')")
    @GetMapping("/modelo-184")
    public ResponseEntity<Modelo184DTO> modelo184(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Long ownerId) {
        return ResponseEntity.ok(taxService.modelo184(yearOrCurrent(year), ownerId));
    }

    /** IRPF: rental income (with deductible expenses) and atribución de rentas of the year, per person. */
    @PreAuthorize("@access.can('IMPUESTOS','LEER')")
    @GetMapping("/irpf")
    public ResponseEntity<IrpfReportDTO> irpf(@RequestParam(required = false) Integer year) {
        return ResponseEntity.ok(taxService.irpf(yearOrCurrent(year)));
    }

    // ------------------------------------------------------------------
    // Filed returns
    // ------------------------------------------------------------------

    @PreAuthorize("@access.can('IMPUESTOS','LEER')")
    @GetMapping("/filings")
    public ResponseEntity<List<TaxFilingDTO>> getFilings(
            @RequestParam(required = false) TaxModel model,
            @RequestParam(required = false) Integer year) {
        return ResponseEntity.ok(taxFilingService.getFilings(model, year));
    }

    @PreAuthorize("@access.can('IMPUESTOS','LEER')")
    @GetMapping("/filings/{id}")
    public ResponseEntity<TaxFilingDTO> getFiling(@PathVariable Long id) {
        return ResponseEntity.ok(taxFilingService.getFilingDtoById(id));
    }

    @PreAuthorize("@access.can('IMPUESTOS','ESCRIBIR')")
    @PostMapping("/filings")
    public ResponseEntity<TaxFilingDTO> createFiling(@Valid @RequestBody TaxFilingRequest request) {
        return new ResponseEntity<>(taxFilingService.createFiling(request), HttpStatus.CREATED);
    }

    @PreAuthorize("@access.can('IMPUESTOS','ADMINISTRAR')")
    @DeleteMapping("/filings/{id}")
    public ResponseEntity<Void> deleteFiling(@PathVariable Long id) {
        taxFilingService.deleteFiling(id);
        return ResponseEntity.noContent().build();
    }
}
