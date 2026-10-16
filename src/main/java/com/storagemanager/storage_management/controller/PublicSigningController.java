package com.storagemanager.storage_management.controller;

import com.storagemanager.storage_management.dto.SigningDTOs;
import com.storagemanager.storage_management.service.DocumentService;
import com.storagemanager.storage_management.service.signing.ContractSigningService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

/**
 * Firmar un contrato desde el móvil de quien firma, sin iniciar sesión (ver
 * {@link ContractSigningService}).
 * <p>
 * Lo que autoriza es el token del enlace, y llega en la cabecera
 * {@value #TOKEN_HEADER} y no en la url, como en la subida desde el móvil: así
 * no queda escrito en los registros de acceso. Esta ruta está abierta y exenta
 * de CSRF en SecurityConfig: no usa cookies, así que no hay nada que falsificar.
 */
@RestController
@RequestMapping("/api/signing")
@RequiredArgsConstructor
public class PublicSigningController {

    static final String TOKEN_HEADER = "X-Sign-Token";

    private final ContractSigningService signing;

    @GetMapping
    public ResponseEntity<SigningDTOs.PublicInfo> info(@RequestHeader(TOKEN_HEADER) String token, HttpServletRequest http) {
        return ResponseEntity.ok(signing.publicInfo(token, RequestOrigin.of(http)));
    }

    @GetMapping("/document")
    public ResponseEntity<InputStreamResource> document(@RequestHeader(TOKEN_HEADER) String token) {
        return pdf(signing.publicDocument(token));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SigningDTOs.PublicInfo> sign(@RequestHeader(TOKEN_HEADER) String token,
                                                       @RequestPart("signature") MultipartFile signature,
                                                       @RequestParam("taxId") String taxId,
                                                       @RequestParam(defaultValue = "false") boolean accepted,
                                                       HttpServletRequest http) {
        return ResponseEntity.ok(signing.publicSign(token, ContractSigningController.bytesOf(signature),
                taxId, accepted, RequestOrigin.of(http)));
    }

    @GetMapping("/signed")
    public ResponseEntity<InputStreamResource> signed(@RequestHeader(TOKEN_HEADER) String token, HttpServletRequest http) {
        return pdf(signing.publicCopy(token, RequestOrigin.of(http)));
    }

    private static ResponseEntity<InputStreamResource> pdf(DocumentService.Content content) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(content.document().getFileName(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new InputStreamResource(content.stream()));
    }
}
