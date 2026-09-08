package com.storagemanager.storage_management.controller;

import com.storagemanager.storage_management.dto.ClientDocumentDTO;
import com.storagemanager.storage_management.model.ClientDocument;
import com.storagemanager.storage_management.model.enums.DocumentType;
import com.storagemanager.storage_management.service.ClientDocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Los ficheros archivados en la ficha de un cliente (copia del DNI, contrato de
 * trabajo, una foto...).
 * <p>
 * La subida va como {@code multipart/form-data} y la descarga la sirve este
 * mismo backend, en vez de enseñar el almacén al navegador: así el bucket no
 * necesita ser público ni hay que firmar URLs, y el día que la aplicación tenga
 * usuarios basta con proteger estas rutas como las demás.
 */
@RestController
@RequestMapping("/api/clients/{clientId}/documents")
@RequiredArgsConstructor
public class ClientDocumentController {

    private final ClientDocumentService documentService;

    /** Los tipos de documento, para el desplegable del formulario. */
    @GetMapping("/types")
    public ResponseEntity<DocumentType[]> getTypes() {
        return ResponseEntity.ok(DocumentType.values());
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

    /**
     * El fichero. Las imágenes y los PDF se mandan "inline" para poder verlos en
     * una pestaña; lo demás se descarga.
     */
    @GetMapping("/{documentId}/download")
    public ResponseEntity<Resource> download(@PathVariable Long clientId, @PathVariable Long documentId) {
        ClientDocumentService.Content content = documentService.download(clientId, documentId);
        ClientDocument document = content.document();

        String contentType = document.getContentType() != null
                ? document.getContentType()
                : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        boolean viewable = contentType.startsWith("image/") || contentType.equals(MediaType.APPLICATION_PDF_VALUE);

        ContentDisposition disposition = (viewable ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(document.getFileName(), StandardCharsets.UTF_8)
                .build();

        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(contentType));
        if (document.getSizeBytes() != null) {
            response.contentLength(document.getSizeBytes());
        }
        return response.body(new InputStreamResource(content.stream()));
    }

    @DeleteMapping("/{documentId}")
    public ResponseEntity<Void> delete(@PathVariable Long clientId, @PathVariable Long documentId) {
        documentService.delete(clientId, documentId);
        return ResponseEntity.noContent().build();
    }
}
