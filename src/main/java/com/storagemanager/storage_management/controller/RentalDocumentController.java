package com.storagemanager.storage_management.controller;

import com.storagemanager.storage_management.dto.RentalDocumentDTO;
import com.storagemanager.storage_management.model.enums.DocumentType;
import com.storagemanager.storage_management.service.RentalDocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Los ficheros de un alquiler: la copia firmada del contrato, sus anexos y las
 * fotos de la entrega. Mismas rutas que las de la ficha del cliente
 * ({@link ClientDocumentController}), colgando del contrato.
 */
@RestController
@RequestMapping("/api/rentals/{rentalId}/documents")
@RequiredArgsConstructor
public class RentalDocumentController {

    private final RentalDocumentService documentService;

    /** Los tipos que admite un alquiler, para el desplegable del formulario. */
    @PreAuthorize("@access.can('ALQUILERES','LEER')")
    @GetMapping("/types")
    public ResponseEntity<List<DocumentType>> getTypes() {
        return ResponseEntity.ok(DocumentType.forRental());
    }

    @PreAuthorize("@access.can('ALQUILERES','LEER')")
    @GetMapping
    public ResponseEntity<List<RentalDocumentDTO>> getDocuments(@PathVariable Long rentalId) {
        return ResponseEntity.ok(documentService.getDocuments(rentalId));
    }

    @PreAuthorize("@access.can('ALQUILERES','ESCRIBIR')")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<RentalDocumentDTO> upload(
            @PathVariable Long rentalId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) DocumentType documentType,
            @RequestParam(required = false) String description) {
        return new ResponseEntity<>(
                documentService.upload(rentalId, file, documentType, description), HttpStatus.CREATED);
    }

    @PreAuthorize("@access.can('ALQUILERES','LEER')")
    @GetMapping("/{documentId}/download")
    public ResponseEntity<Resource> download(@PathVariable Long rentalId, @PathVariable Long documentId) {
        return DocumentDownload.respond(documentService.download(rentalId, documentId));
    }

    @PreAuthorize("@access.can('ALQUILERES','ADMINISTRAR')")
    @DeleteMapping("/{documentId}")
    public ResponseEntity<Void> delete(@PathVariable Long rentalId, @PathVariable Long documentId) {
        documentService.delete(rentalId, documentId);
        return ResponseEntity.noContent().build();
    }
}
