package com.storagemanager.storage_management.controller;

import com.storagemanager.storage_management.dto.ClientDocumentDTO;
import com.storagemanager.storage_management.model.enums.DocumentType;
import com.storagemanager.storage_management.service.ClientDocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Los ficheros archivados en la ficha de un cliente (copia del DNI, contrato de
 * trabajo, una foto...). La copia firmada del contrato de alquiler no está aquí:
 * cuelga del alquiler, en {@link RentalDocumentController}.
 * <p>
 * La subida va como {@code multipart/form-data} y la descarga la sirve este
 * mismo backend ({@link DocumentDownload}), en vez de enseñar el almacén al
 * navegador: así el bucket no necesita ser público ni hay que firmar URLs, y el
 * día que la aplicación tenga usuarios basta con proteger estas rutas como las
 * demás.
 */
@RestController
@RequestMapping("/api/clients/{clientId}/documents")
@RequiredArgsConstructor
public class ClientDocumentController {

    private final ClientDocumentService documentService;

    /**
     * Los tipos que admite la ficha del cliente, para el desplegable del
     * formulario. CONTRATO_ALQUILER no está: el contrato va en su alquiler.
     */
    @GetMapping("/types")
    public ResponseEntity<List<DocumentType>> getTypes() {
        return ResponseEntity.ok(DocumentType.forClient());
    }

    @GetMapping
    public ResponseEntity<List<ClientDocumentDTO>> getDocuments(@PathVariable Long clientId) {
        return ResponseEntity.ok(documentService.getDocuments(clientId));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ClientDocumentDTO> upload(
            @PathVariable Long clientId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) DocumentType documentType,
            @RequestParam(required = false) String description) {
        return new ResponseEntity<>(
                documentService.upload(clientId, file, documentType, description), HttpStatus.CREATED);
    }

    @GetMapping("/{documentId}/download")
    public ResponseEntity<Resource> download(@PathVariable Long clientId, @PathVariable Long documentId) {
        return DocumentDownload.respond(documentService.download(clientId, documentId));
    }

    @DeleteMapping("/{documentId}")
    public ResponseEntity<Void> delete(@PathVariable Long clientId, @PathVariable Long documentId) {
        documentService.delete(clientId, documentId);
        return ResponseEntity.noContent().build();
    }
}
