package com.storagemanager.storage_management.controller;

import com.storagemanager.storage_management.dto.RentalAgreementRequest;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.enums.RentalStatus;
import com.storagemanager.storage_management.service.ContractService;
import com.storagemanager.storage_management.service.RentalAgreementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/rentals")
@RequiredArgsConstructor

public class RentalAgreementController {

    private final RentalAgreementService rentalAgreementService;
    private final ContractService contractService;

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

    /**
     * Compone el contrato y lo devuelve para leerlo, SIN archivar nada. Es el
     * primer paso: se mira, y si hay algo que corregir se corrige y se vuelve a
     * pedir, sin que quede ningún fichero por medio.
     */
    @PreAuthorize("@access.can('ALQUILERES','ESCRIBIR')")
    @PostMapping("/{id}/contract/preview")
    public ResponseEntity<Resource> previewContract(@PathVariable Long id) {
        ContractService.Draft draft = contractService.preview(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(draft.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(draft.content().length)
                .body(new ByteArrayResource(draft.content()));
    }

    /**
     * El segundo paso: archiva el contrato entre los documentos del alquiler y
     * lo devuelve. Si ya había uno generado, lo sustituye. Es un borrador para
     * firmar, no un documento emitido: rehacerlo no tiene coste.
     */
    @PreAuthorize("@access.can('ALQUILERES','ESCRIBIR')")
    @PostMapping("/{id}/contract")
    public ResponseEntity<Resource> generateContract(@PathVariable Long id) {
        return DocumentDownload.respond(contractService.generateAndOpen(id));
    }
}
