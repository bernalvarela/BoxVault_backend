package com.storagemanager.storage_management.controller;

import com.storagemanager.storage_management.dto.ClientDocumentDTO;
import com.storagemanager.storage_management.dto.MobileUploadDTOs;
import com.storagemanager.storage_management.service.MobileUploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Subir un documento de la ficha de un cliente con el móvil (ver
 * {@link MobileUploadService}).
 * <p>
 * Tiene dos mitades. La del ordenador va con sesión y permisos, como el resto de
 * la ficha. La del móvil, {@code /api/mobile-upload}, va sin sesión: lo que
 * autoriza es el token del QR, y llega en la cabecera {@value #TOKEN_HEADER} y
 * no en la url para que no se quede escrito en los registros de acceso. Esa
 * mitad está abierta y exenta de CSRF en SecurityConfig: no usa cookies, así
 * que no hay nada que falsificar.
 */
@RestController
@RequiredArgsConstructor
public class MobileUploadController {

    static final String TOKEN_HEADER = "X-Upload-Token";

    private final MobileUploadService mobileUploads;

    @PreAuthorize("@access.can('CLIENTES','ESCRIBIR')")
    @PostMapping("/api/clients/{clientId}/documents/mobile-uploads")
    public ResponseEntity<MobileUploadDTOs.Created> create(
            @PathVariable Long clientId, @RequestBody MobileUploadDTOs.CreateRequest request) {
        return new ResponseEntity<>(mobileUploads.create(clientId, request), HttpStatus.CREATED);
    }

    @PreAuthorize("@access.can('CLIENTES','ESCRIBIR')")
    @GetMapping("/api/clients/{clientId}/documents/mobile-uploads/{id}")
    public ResponseEntity<MobileUploadDTOs.Status> status(@PathVariable Long clientId, @PathVariable String id) {
        return ResponseEntity.ok(mobileUploads.status(clientId, id));
    }

    @PreAuthorize("@access.can('CLIENTES','ESCRIBIR')")
    @DeleteMapping("/api/clients/{clientId}/documents/mobile-uploads/{id}")
    public ResponseEntity<Void> cancel(@PathVariable Long clientId, @PathVariable String id) {
        mobileUploads.cancel(clientId, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/mobile-upload")
    public ResponseEntity<MobileUploadDTOs.Info> info(@RequestHeader(TOKEN_HEADER) String token) {
        return ResponseEntity.ok(mobileUploads.info(token));
    }

    @PostMapping(value = "/api/mobile-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ClientDocumentDTO> upload(
            @RequestHeader(TOKEN_HEADER) String token, @RequestPart("file") MultipartFile file) {
        return new ResponseEntity<>(mobileUploads.upload(token, file), HttpStatus.CREATED);
    }
}
