package com.storagemanager.storage_management.controller;

import com.storagemanager.storage_management.dto.SigningDTOs;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.service.signing.ContractSigningService;
import com.storagemanager.storage_management.service.signing.SignedContractMailer;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * La firma de un contrato desde BoxVault: prepararla, pedir los enlaces, firmar
 * en persona en la tableta y cancelarla (ver {@link ContractSigningService}).
 * <p>
 * Los PDF —el congelado y el firmado— se descargan como cualquier otro
 * documento del alquiler: son documentos suyos.
 */
@RestController
@RequestMapping("/api/rentals/{rentalId}/signings")
@RequiredArgsConstructor
public class ContractSigningController {

    private final ContractSigningService signing;
    private final SignedContractMailer mailer;

    @PreAuthorize("@access.can('ALQUILERES','LEER')")
    @GetMapping
    public ResponseEntity<SigningDTOs.Overview> overview(@PathVariable Long rentalId) {
        return ResponseEntity.ok(signing.overview(rentalId));
    }

    @PreAuthorize("@access.can('ALQUILERES','ESCRIBIR')")
    @PostMapping
    public ResponseEntity<SigningDTOs.SigningView> prepare(@PathVariable Long rentalId,
                                                           @RequestBody SigningDTOs.PrepareRequest request,
                                                           HttpServletRequest http) {
        return new ResponseEntity<>(signing.prepare(rentalId, request, RequestOrigin.of(http)), HttpStatus.CREATED);
    }

    @PreAuthorize("@access.can('ALQUILERES','ESCRIBIR')")
    @PostMapping("/{signingId}/cancel")
    public ResponseEntity<SigningDTOs.SigningView> cancel(@PathVariable Long rentalId, @PathVariable Long signingId,
                                                          @RequestBody(required = false) SigningDTOs.CancelRequest request,
                                                          HttpServletRequest http) {
        return ResponseEntity.ok(signing.cancel(rentalId, signingId,
                request == null ? null : request.reason(), RequestOrigin.of(http)));
    }

    @PreAuthorize("@access.can('ALQUILERES','ESCRIBIR')")
    @PostMapping("/{signingId}/signers/{signerId}/link")
    public ResponseEntity<SigningDTOs.LinkView> link(@PathVariable Long rentalId, @PathVariable Long signingId,
                                                     @PathVariable Long signerId,
                                                     @RequestBody(required = false) SigningDTOs.LinkRequest request,
                                                     HttpServletRequest http) {
        return ResponseEntity.ok(signing.link(rentalId, signingId, signerId,
                request == null ? null : request.origin(), RequestOrigin.of(http)));
    }

    /** Un código para entregárselo al firmante a mano (WhatsApp, SMS, teléfono). Se enseña una vez. */
    @PreAuthorize("@access.can('ALQUILERES','ESCRIBIR')")
    @PostMapping("/{signingId}/signers/{signerId}/code")
    public ResponseEntity<SigningDTOs.ManualCode> manualCode(@PathVariable Long rentalId, @PathVariable Long signingId,
                                                             @PathVariable Long signerId, HttpServletRequest http) {
        return ResponseEntity.ok(signing.manualCode(rentalId, signingId, signerId, RequestOrigin.of(http)));
    }

    @PreAuthorize("@access.can('ALQUILERES','ESCRIBIR')")
    @PostMapping(value = "/{signingId}/signers/{signerId}/sign", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SigningDTOs.SigningView> signInPerson(@PathVariable Long rentalId, @PathVariable Long signingId,
                                                                @PathVariable Long signerId,
                                                                @RequestPart("signature") MultipartFile signature,
                                                                @RequestParam(defaultValue = "false") boolean identityChecked,
                                                                HttpServletRequest http) {
        return ResponseEntity.ok(signing.signInPerson(rentalId, signingId, signerId,
                bytesOf(signature), identityChecked, RequestOrigin.of(http)));
    }

    @PreAuthorize("@access.can('ALQUILERES','ESCRIBIR')")
    @PostMapping("/{signingId}/seal")
    public ResponseEntity<SigningDTOs.SigningView> retrySeal(@PathVariable Long rentalId, @PathVariable Long signingId,
                                                             HttpServletRequest http) {
        return ResponseEntity.ok(signing.retrySeal(rentalId, signingId, RequestOrigin.of(http)));
    }

    /** Vuelve a mandar por correo el contrato firmado a todos los que tienen correo. */
    @PreAuthorize("@access.can('ALQUILERES','ESCRIBIR')")
    @PostMapping("/{signingId}/email")
    public ResponseEntity<SignedContractMailer.Result> resendEmail(@PathVariable Long rentalId, @PathVariable Long signingId) {
        return ResponseEntity.ok(mailer.resend(rentalId, signingId));
    }

    static byte[] bytesOf(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BadRequestException("Falta la firma");
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BadRequestException("No se pudo leer la firma");
        }
    }
}
