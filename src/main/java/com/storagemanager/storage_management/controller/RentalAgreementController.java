package com.storagemanager.storage_management.controller;

import com.storagemanager.storage_management.dto.RentalAgreementRequest;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.enums.RentalStatus;
import com.storagemanager.storage_management.service.RentalAgreementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
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

    @GetMapping
    public ResponseEntity<List<RentalAgreement>> getAllRentals(@RequestParam(required = false) RentalStatus status) {
        if (status == RentalStatus.ACTIVE) {
            return ResponseEntity.ok(rentalAgreementService.getActiveAgreements());
        }
        return ResponseEntity.ok(rentalAgreementService.getAllAgreements());
    }

    @GetMapping("/{id}")
    public ResponseEntity<RentalAgreement> getRentalById(@PathVariable Long id) {
        return ResponseEntity.ok(rentalAgreementService.getAgreementById(id));
    }

    @PostMapping
    public ResponseEntity<RentalAgreement> createRental(@Valid @RequestBody RentalAgreementRequest request) {
        return new ResponseEntity<>(rentalAgreementService.createAgreement(request), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    public ResponseEntity<RentalAgreement> updateRental(@PathVariable Long id, @Valid @RequestBody RentalAgreementRequest request) {
        return ResponseEntity.ok(rentalAgreementService.updateAgreement(id, request));
    }

    @PostMapping("/{id}/terminate")
    public ResponseEntity<RentalAgreement> terminateRental(
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate terminationDate) {
        return ResponseEntity.ok(rentalAgreementService.terminateAgreement(id, terminationDate));
    }
}
