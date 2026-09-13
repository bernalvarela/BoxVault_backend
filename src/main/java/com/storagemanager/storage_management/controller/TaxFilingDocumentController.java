package com.storagemanager.storage_management.controller;

import com.storagemanager.storage_management.dto.TaxFilingDocumentDTO;
import com.storagemanager.storage_management.model.enums.DocumentType;
import com.storagemanager.storage_management.service.TaxFilingDocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Los documentos que acreditan una declaración presentada: el justificante con
 * el CSV de la Sede electrónica, el PDF de la declaración o el fichero que se
 * importó. Mismo trato que los de un cliente o un alquiler, con los permisos del
 * área de IMPUESTOS.
 */
@RestController
@RequestMapping("/api/taxes/filings/{filingId}/documents")
@RequiredArgsConstructor
public class TaxFilingDocumentController {

    private final TaxFilingDocumentService documentService;

    /** Los tipos que admite una declaración, para el desplegable del formulario. */
    @PreAuthorize("@access.can('IMPUESTOS','LEER')")
    @GetMapping("/types")
    public ResponseEntity<List<DocumentType>> getTypes() {
        return ResponseEntity.ok(DocumentType.forTaxFiling());
    }

    @PreAuthorize("@access.can('IMPUESTOS','LEER')")
    @GetMapping
    public ResponseEntity<List<TaxFilingDocumentDTO>> getDocuments(@PathVariable Long filingId) {
        return ResponseEntity.ok(documentService.getDocuments(filingId));
    }

    @PreAuthorize("@access.can('IMPUESTOS','ESCRIBIR')")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TaxFilingDocumentDTO> upload(
            @PathVariable Long filingId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) DocumentType documentType,
            @RequestParam(required = false) String description) {
        return new ResponseEntity<>(
                documentService.upload(filingId, file, documentType, description), HttpStatus.CREATED);
    }

    @PreAuthorize("@access.can('IMPUESTOS','LEER')")
    @GetMapping("/{documentId}/download")
    public ResponseEntity<Resource> download(@PathVariable Long filingId, @PathVariable Long documentId) {
        return DocumentDownload.respond(documentService.download(filingId, documentId));
    }

    @PreAuthorize("@access.can('IMPUESTOS','ADMINISTRAR')")
    @DeleteMapping("/{documentId}")
    public ResponseEntity<Void> delete(@PathVariable Long filingId, @PathVariable Long documentId) {
        documentService.delete(filingId, documentId);
        return ResponseEntity.noContent().build();
    }
}
