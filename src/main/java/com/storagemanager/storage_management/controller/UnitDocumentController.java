package com.storagemanager.storage_management.controller;

import com.storagemanager.storage_management.dto.ExpenseDocumentDTO;
import com.storagemanager.storage_management.dto.UnitDocumentEntryDTO;
import com.storagemanager.storage_management.model.enums.DocumentType;
import com.storagemanager.storage_management.service.ExpenseDocumentService;
import com.storagemanager.storage_management.service.UnitDocumentService;
import jakarta.validation.Valid;
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
 * Los documentos de una unidad, agrupados en entradas con uno o más ficheros.
 * Permisos del área de UNIDADES.
 */
@RestController
@RequestMapping("/api/storages")
@RequiredArgsConstructor
public class UnitDocumentController {

    private final UnitDocumentService unitDocumentService;
    private final ExpenseDocumentService expenseDocumentService;

    @PreAuthorize("@access.can('UNIDADES','LEER')")
    @GetMapping("/document-types")
    public ResponseEntity<List<DocumentType>> getDocumentTypes() {
        return ResponseEntity.ok(DocumentType.forUnit());
    }

    @PreAuthorize("@access.can('UNIDADES','LEER')")
    @GetMapping("/{unitId}/document-entries")
    public ResponseEntity<List<UnitDocumentEntryDTO>> getEntries(@PathVariable Long unitId) {
        return ResponseEntity.ok(unitDocumentService.getEntries(unitId));
    }

    /** Los justificantes de los gastos de la unidad, con los datos del gasto. Pide ver también GASTOS. */
    @PreAuthorize("@access.can('UNIDADES','LEER') and @access.can('GASTOS','LEER')")
    @GetMapping("/{unitId}/expense-documents")
    public ResponseEntity<List<ExpenseDocumentDTO>> getExpenseDocuments(@PathVariable Long unitId) {
        return ResponseEntity.ok(expenseDocumentService.getDocumentsForUnit(unitId));
    }

    @PreAuthorize("@access.can('UNIDADES','ESCRIBIR')")
    @PostMapping("/{unitId}/document-entries")
    public ResponseEntity<UnitDocumentEntryDTO> createEntry(
            @PathVariable Long unitId, @Valid @RequestBody UnitDocumentEntryDTO.Request request) {
        return new ResponseEntity<>(unitDocumentService.createEntry(unitId, request), HttpStatus.CREATED);
    }

    @PreAuthorize("@access.can('UNIDADES','ESCRIBIR')")
    @PutMapping("/{unitId}/document-entries/{entryId}")
    public ResponseEntity<UnitDocumentEntryDTO> updateEntry(
            @PathVariable Long unitId, @PathVariable Long entryId,
            @Valid @RequestBody UnitDocumentEntryDTO.Request request) {
        return ResponseEntity.ok(unitDocumentService.updateEntry(unitId, entryId, request));
    }

    @PreAuthorize("@access.can('UNIDADES','ADMINISTRAR')")
    @DeleteMapping("/{unitId}/document-entries/{entryId}")
    public ResponseEntity<Void> deleteEntry(@PathVariable Long unitId, @PathVariable Long entryId) {
        unitDocumentService.deleteEntry(unitId, entryId);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("@access.can('UNIDADES','ESCRIBIR')")
    @PostMapping(value = "/{unitId}/document-entries/{entryId}/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UnitDocumentEntryDTO> upload(
            @PathVariable Long unitId, @PathVariable Long entryId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) DocumentType documentType,
            @RequestParam(required = false) String description) {
        return new ResponseEntity<>(
                unitDocumentService.upload(unitId, entryId, file, documentType, description), HttpStatus.CREATED);
    }

    @PreAuthorize("@access.can('UNIDADES','LEER')")
    @GetMapping("/{unitId}/document-entries/{entryId}/documents/{documentId}/download")
    public ResponseEntity<Resource> download(
            @PathVariable Long unitId, @PathVariable Long entryId, @PathVariable Long documentId) {
        return DocumentDownload.respond(unitDocumentService.download(unitId, entryId, documentId));
    }

    @PreAuthorize("@access.can('UNIDADES','ADMINISTRAR')")
    @DeleteMapping("/{unitId}/document-entries/{entryId}/documents/{documentId}")
    public ResponseEntity<UnitDocumentEntryDTO> deleteDocument(
            @PathVariable Long unitId, @PathVariable Long entryId, @PathVariable Long documentId) {
        return ResponseEntity.ok(unitDocumentService.deleteDocument(unitId, entryId, documentId));
    }
}
