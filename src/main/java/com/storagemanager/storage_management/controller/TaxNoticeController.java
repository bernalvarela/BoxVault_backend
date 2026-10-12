package com.storagemanager.storage_management.controller;

import com.storagemanager.storage_management.dto.TaxNoticeDTO;
import com.storagemanager.storage_management.dto.TaxNoticeDocumentDTO;
import com.storagemanager.storage_management.dto.TaxNoticePaymentRequest;
import com.storagemanager.storage_management.dto.TaxNoticeRequest;
import com.storagemanager.storage_management.model.enums.DocumentType;
import com.storagemanager.storage_management.model.enums.TaxModel;
import com.storagemanager.storage_management.service.TaxNoticeService;
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
 * Las notificaciones de la AEAT: requerimientos, sanciones, recargos... con sus
 * documentos y su pago. Permisos del área de IMPUESTOS; apuntar el pago crea o
 * cambia un gasto, así que pide además poder escribir en GASTOS.
 */
@RestController
@RequestMapping("/api/taxes/notices")
@RequiredArgsConstructor
public class TaxNoticeController {

    private final TaxNoticeService noticeService;

    @PreAuthorize("@access.can('IMPUESTOS','LEER')")
    @GetMapping
    public ResponseEntity<List<TaxNoticeDTO>> getNotices(@RequestParam(required = false) TaxModel model) {
        return ResponseEntity.ok(noticeService.getNotices(model));
    }

    @PreAuthorize("@access.can('IMPUESTOS','LEER')")
    @GetMapping("/{id}")
    public ResponseEntity<TaxNoticeDTO> getNotice(@PathVariable Long id) {
        return ResponseEntity.ok(noticeService.getNotice(id));
    }

    @PreAuthorize("@access.can('IMPUESTOS','ESCRIBIR')")
    @PostMapping
    public ResponseEntity<TaxNoticeDTO> create(@Valid @RequestBody TaxNoticeRequest request) {
        return new ResponseEntity<>(noticeService.create(request), HttpStatus.CREATED);
    }

    @PreAuthorize("@access.can('IMPUESTOS','ESCRIBIR')")
    @PutMapping("/{id}")
    public ResponseEntity<TaxNoticeDTO> update(@PathVariable Long id, @Valid @RequestBody TaxNoticeRequest request) {
        return ResponseEntity.ok(noticeService.update(id, request));
    }

    @PreAuthorize("@access.can('IMPUESTOS','ADMINISTRAR')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        noticeService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("@access.can('IMPUESTOS','ESCRIBIR') and @access.can('GASTOS','ESCRIBIR')")
    @PostMapping("/{id}/payment")
    public ResponseEntity<TaxNoticeDTO> pay(@PathVariable Long id, @Valid @RequestBody TaxNoticePaymentRequest request) {
        return ResponseEntity.ok(noticeService.pay(id, request));
    }

    @PreAuthorize("@access.can('IMPUESTOS','ESCRIBIR')")
    @DeleteMapping("/{id}/payment")
    public ResponseEntity<TaxNoticeDTO> unpay(@PathVariable Long id) {
        return ResponseEntity.ok(noticeService.unpay(id));
    }

    // ---- Documentos

    /** Los tipos que admite una notificación, para el desplegable del formulario. */
    @PreAuthorize("@access.can('IMPUESTOS','LEER')")
    @GetMapping("/document-types")
    public ResponseEntity<List<DocumentType>> getDocumentTypes() {
        return ResponseEntity.ok(DocumentType.forTaxNotice());
    }

    @PreAuthorize("@access.can('IMPUESTOS','LEER')")
    @GetMapping("/{id}/documents")
    public ResponseEntity<List<TaxNoticeDocumentDTO>> getDocuments(@PathVariable Long id) {
        return ResponseEntity.ok(noticeService.getDocuments(id));
    }

    @PreAuthorize("@access.can('IMPUESTOS','ESCRIBIR')")
    @PostMapping(value = "/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TaxNoticeDocumentDTO> upload(
            @PathVariable Long id,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) DocumentType documentType,
            @RequestParam(required = false) String description) {
        return new ResponseEntity<>(noticeService.upload(id, file, documentType, description), HttpStatus.CREATED);
    }

    @PreAuthorize("@access.can('IMPUESTOS','LEER')")
    @GetMapping("/{id}/documents/{documentId}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id, @PathVariable Long documentId) {
        return DocumentDownload.respond(noticeService.download(id, documentId));
    }

    @PreAuthorize("@access.can('IMPUESTOS','ADMINISTRAR')")
    @DeleteMapping("/{id}/documents/{documentId}")
    public ResponseEntity<Void> deleteDocument(@PathVariable Long id, @PathVariable Long documentId) {
        noticeService.deleteDocument(id, documentId);
        return ResponseEntity.noContent().build();
    }
}
