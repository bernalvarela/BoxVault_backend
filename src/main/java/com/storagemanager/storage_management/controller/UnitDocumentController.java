package com.storagemanager.storage_management.controller;

import com.storagemanager.storage_management.dto.ExpenseDocumentDTO;
import com.storagemanager.storage_management.dto.UnitDocumentEntryDTO;
import com.storagemanager.storage_management.model.enums.DocumentType;
import com.storagemanager.storage_management.security.Authenticated;
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
    private final Authenticated access;

    @PreAuthorize("@access.can('UNIDADES','LEER')")
    @GetMapping("/document-types")
    public ResponseEntity<List<DocumentType>> getDocumentTypes() {
        return ResponseEntity.ok(DocumentType.forUnit());
    }

    @PreAuthorize("@access.can('UNIDADES','LEER')")
    @GetMapping("/{unitId}/document-entries")
    public ResponseEntity<List<UnitDocumentEntryDTO>> getEntries(@PathVariable Long unitId) {
        // Los gastos relacionados con cada entrada, sólo para quien puede ver gastos.
        return ResponseEntity.ok(unitDocumentService.getEntries(unitId, access.can("GASTOS", "LEER")));
    }

    /** Relaciona un gasto de la unidad con la entrada, para que salgan agrupados. */
    @PreAuthorize("@access.can('UNIDADES','ESCRIBIR') and @access.can('GASTOS','LEER')")
    @PutMapping("/{unitId}/document-entries/{entryId}/expenses/{expenseId}")
    public ResponseEntity<Void> linkExpense(
            @PathVariable Long unitId, @PathVariable Long entryId, @PathVariable Long expenseId) {
        unitDocumentService.linkExpense(unitId, entryId, expenseId);
        return ResponseEntity.noContent().build();
    }

    /** Quita la relación; el gasto y sus documentos se quedan. */
    @PreAuthorize("@access.can('UNIDADES','ESCRIBIR') and @access.can('GASTOS','LEER')")
    @DeleteMapping("/{unitId}/document-entries/{entryId}/expenses/{expenseId}")
    public ResponseEntity<Void> unlinkExpense(
            @PathVariable Long unitId, @PathVariable Long entryId, @PathVariable Long expenseId) {
        unitDocumentService.unlinkExpense(unitId, entryId, expenseId);
        return ResponseEntity.noContent().build();
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

    /** Cambia el tipo y la descripción de un fichero, sin volver a subirlo. */
    @PreAuthorize("@access.can('UNIDADES','ESCRIBIR')")
    @PutMapping("/{unitId}/document-entries/{entryId}/documents/{documentId}")
    public ResponseEntity<UnitDocumentEntryDTO> updateDocument(
            @PathVariable Long unitId, @PathVariable Long entryId, @PathVariable Long documentId,
            @Valid @RequestBody UnitDocumentEntryDTO.FileUpdate request) {
        return ResponseEntity.ok(unitDocumentService.updateDocument(
                unitId, entryId, documentId, request.getDocumentType(), request.getDescription()));
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
