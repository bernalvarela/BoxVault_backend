package com.storagemanager.storage_management.controller;

import com.storagemanager.storage_management.dto.RentalAgreementRequest;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.enums.RentalStatus;
import com.storagemanager.storage_management.service.RentalAgreementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/rentals")
@RequiredArgsConstructor

public class RentalAgreementController {

    private final RentalAgreementService rentalAgreementService;

    @PreAuthorize("@access.can('ALQUILERES','LEER')")
    @GetMapping
    public ResponseEntity<List<RentalAgreement>> getAllRentals(@RequestParam(required = false) RentalStatus status) {
        if (status == RentalStatus.ACTIVE) {
            return ResponseEntity.ok(rentalAgreementService.getActiveAgreements());
        }
        return ResponseEntity.ok(rentalAgreementService.getAllAgreements());
    }

    @PreAuthorize("@access.can('ALQUILERES','LEER')")
    @GetMapping("/{id}")
    public ResponseEntity<RentalAgreement> getRentalById(@PathVariable Long id) {
        return ResponseEntity.ok(rentalAgreementService.getAgreementById(id));
    }

    @PreAuthorize("@access.can('ALQUILERES','ESCRIBIR')")
    @PostMapping
    public ResponseEntity<RentalAgreement> createRental(@Valid @RequestBody RentalAgreementRequest request) {
        return new ResponseEntity<>(rentalAgreementService.createAgreement(request), HttpStatus.CREATED);
    }

    @PreAuthorize("@access.can('ALQUILERES','ESCRIBIR')")
    @PutMapping("/{id}")
    public ResponseEntity<RentalAgreement> updateRental(@PathVariable Long id, @Valid @RequestBody RentalAgreementRequest request) {
        return ResponseEntity.ok(rentalAgreementService.updateAgreement(id, request));
    }

    /**
     * Vuelve a poner en vigor un contrato terminado por error (una fecha de fin
     * equivocada, una migración que lo cerró).
     */
    @PreAuthorize("@access.can('ALQUILERES','ESCRIBIR')")
    @PostMapping("/{id}/reactivate")
    public ResponseEntity<RentalAgreement> reactivate(@PathVariable Long id) {
        return ResponseEntity.ok(rentalAgreementService.reactivate(id));
    }

    /**
     * Une un contrato duplicado de la misma unidad a éste: sus cobros y sus
     * documentos pasan aquí y el duplicado se borra.
     * <p>
     * Pide ADMINISTRAR y no ESCRIBIR porque borra un contrato: es una corrección
     * de datos, no la operación de cada día.
     */
    @PreAuthorize("@access.can('ALQUILERES','ADMINISTRAR')")
    @PostMapping("/{id}/absorb/{sourceId}")
    public ResponseEntity<RentalAgreement> absorb(@PathVariable Long id, @PathVariable Long sourceId) {
        return ResponseEntity.ok(rentalAgreementService.absorb(id, sourceId));
    }

    @PreAuthorize("@access.can('ALQUILERES','ESCRIBIR')")
    @PostMapping("/{id}/terminate")
    public ResponseEntity<RentalAgreement> terminateRental(
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate terminationDate) {
        return ResponseEntity.ok(rentalAgreementService.terminateAgreement(id, terminationDate));
    }
}
