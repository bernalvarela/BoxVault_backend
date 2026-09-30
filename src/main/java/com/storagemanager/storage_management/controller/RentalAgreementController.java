package com.storagemanager.storage_management.controller;

import com.storagemanager.storage_management.dto.DepositFormDTO;
import com.storagemanager.storage_management.dto.DepositLodgingRequest;
import com.storagemanager.storage_management.dto.RentalAgreementRequest;
import com.storagemanager.storage_management.dto.TerminationRequest;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.enums.RentalStatus;
import com.storagemanager.storage_management.service.ContractService;
import com.storagemanager.storage_management.service.DepositLodgingService;
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
    private final DepositLodgingService depositLodging;

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

    /**
     * Cierra el contrato. El cuerpo, opcional, dice qué pasa con la fianza y si
     * se archiva el contrato de salida; sin cuerpo vale la fecha de la URL, como
     * antes.
     */
    @PreAuthorize("@access.can('ALQUILERES','ESCRIBIR')")
    @PostMapping("/{id}/terminate")
    public ResponseEntity<RentalAgreement> terminateRental(
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate terminationDate,
            @RequestBody(required = false) TerminationRequest request) {
        TerminationRequest termination = request != null ? request : new TerminationRequest();
        if (termination.getTerminationDate() == null) termination.setTerminationDate(terminationDate);
        return ResponseEntity.ok(contractService.terminate(id, termination));
    }

    /**
     * Guarda la fianza del contrato desde su pestaña: el total entregado, la
     * parte de garantía, si está cobrada y su depósito en el IGVS con la
     * devolución. El trámite del IGVS se hace en la sede de la Xunta; aquí solo
     * queda constancia.
     */
    @PreAuthorize("@access.can('ALQUILERES','ESCRIBIR')")
    @PutMapping("/{id}/deposit")
    public ResponseEntity<RentalAgreement> updateDeposit(@PathVariable Long id,
                                                         @RequestBody DepositLodgingRequest request) {
        return ResponseEntity.ok(depositLodging.update(id, request));
    }

    /** Los datos del alquiler que pide el formulario VI436A del IGVS. */
    @PreAuthorize("@access.can('ALQUILERES','LEER')")
    @GetMapping("/{id}/deposit/igvs-form")
    public ResponseEntity<DepositFormDTO> depositLodgingForm(@PathVariable Long id) {
        return ResponseEntity.ok(depositLodging.form(id));
    }

    /**
     * El contrato de salida tal como quedaría con lo que se va a decidir al
     * cerrar, para leerlo ANTES de confirmar. No cierra ni archiva nada.
     */
    @PreAuthorize("@access.can('ALQUILERES','ESCRIBIR')")
    @PostMapping("/{id}/exit-contract/preview")
    public ResponseEntity<Resource> previewExitContract(@PathVariable Long id,
                                                        @RequestBody(required = false) TerminationRequest request) {
        return pdfInline(contractService.previewExit(id, request != null ? request : new TerminationRequest()));
    }

    /**
     * Genera (o rehace) el contrato de salida de un contrato ya finalizado, con
     * lo que diga el cuerpo sobre la fianza. La fecha de salida es la del cierre.
     */
    @PreAuthorize("@access.can('ALQUILERES','ESCRIBIR')")
    @PostMapping("/{id}/exit-contract")
    public ResponseEntity<RentalAgreement> generateExitContract(@PathVariable Long id,
                                                                @RequestBody(required = false) TerminationRequest request) {
        return ResponseEntity.ok(contractService.generateExitAfterwards(id,
                request != null ? request : new TerminationRequest()));
    }

    /**
     * Compone el contrato y lo devuelve para leerlo, SIN archivar nada. Es el
     * primer paso: se mira, y si hay algo que corregir se corrige y se vuelve a
     * pedir, sin que quede ningún fichero por medio.
     */
    @PreAuthorize("@access.can('ALQUILERES','ESCRIBIR')")
    @PostMapping("/{id}/contract/preview")
    public ResponseEntity<Resource> previewContract(@PathVariable Long id) {
        return pdfInline(contractService.preview(id));
    }

    private static ResponseEntity<Resource> pdfInline(ContractService.Draft draft) {
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
