package com.storagemanager.storage_management.controller;

import com.storagemanager.storage_management.model.Document;
import com.storagemanager.storage_management.service.DocumentService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

/**
 * La respuesta que devuelve un fichero archivado, igual venga de la ficha de un
 * cliente o de un alquiler: la sirve el propio backend en vez de enseñar el
 * almacén al navegador, así que el bucket no necesita ser público ni hay que
 * firmar URLs.
 */
final class DocumentDownload {

    private DocumentDownload() {}

    /**
     * Las imágenes y los PDF se mandan "inline", para poder verlos dentro de la
     * aplicación; lo demás se descarga.
     */
    static ResponseEntity<Resource> respond(DocumentService.Content content) {
        Document document = content.document();

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
}
