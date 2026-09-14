package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.dto.TaxFilingDocumentDTO;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.Document;
import com.storagemanager.storage_management.model.TaxFiling;
import com.storagemanager.storage_management.model.TaxFilingDocument;
import com.storagemanager.storage_management.model.enums.DocumentType;
import com.storagemanager.storage_management.repository.TaxFilingDocumentRepository;
import com.storagemanager.storage_management.repository.TaxFilingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Los documentos archivados en una declaración presentada: el justificante con
 * el CSV que devuelve la Sede electrónica, el PDF de la declaración o el propio
 * fichero que se importó. Es lo que acredita la presentación, así que vive con
 * la declaración y no en la ficha de nadie.
 * <p>
 * Igual que {@link ClientDocumentService}, este servicio sólo lleva la relación
 * declaración–documento: del fichero se encarga {@link DocumentService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaxFilingDocumentService {

    private final TaxFilingDocumentRepository filingDocumentRepository;
    private final TaxFilingRepository taxFilingRepository;
    private final DocumentService documents;

    public List<TaxFilingDocumentDTO> getDocuments(Long filingId) {
        requireFiling(filingId);
        return filingDocumentRepository.findByTaxFilingIdOrderByDocumentUploadedAtDescDocumentIdDesc(filingId).stream()
                .map(TaxFilingDocumentDTO::of)
                .toList();
    }

    @Transactional
    public TaxFilingDocumentDTO upload(Long filingId, MultipartFile file, DocumentType type, String description) {
        TaxFiling filing = requireFiling(filingId);
        Document document = documents.store(
                "tax-filings/" + filingId, file, requireFilingType(type), description);

        try {
            TaxFilingDocument saved = filingDocumentRepository.save(TaxFilingDocument.builder()
                    .taxFiling(filing)
                    .document(document)
                    .build());
            return TaxFilingDocumentDTO.of(saved);
        } catch (RuntimeException e) {
            // Sin relación, el documento no es de nadie: no puede quedarse.
            documents.delete(document);
            throw e;
        }
    }

    /**
     * Archiva en la declaración un fichero que ha generado la aplicación: el .303
     * que se lleva a la Sede. Así lo presentado y lo que se subió quedan juntos.
     */
    @Transactional
    public TaxFilingDocumentDTO attach(Long filingId, String fileName, String contentType, byte[] content,
                                       DocumentType type, String description) {
        TaxFiling filing = requireFiling(filingId);
        Document document = documents.store("tax-filings/" + filingId, fileName, contentType, content,
                requireFilingType(type), description);
        try {
            return TaxFilingDocumentDTO.of(filingDocumentRepository.save(TaxFilingDocument.builder()
                    .taxFiling(filing)
                    .document(document)
                    .build()));
        } catch (RuntimeException e) {
            documents.delete(document);
            throw e;
        }
    }

    public DocumentService.Content download(Long filingId, Long documentId) {
        return documents.open(requireLink(filingId, documentId).getDocument());
    }

    @Transactional
    public void delete(Long filingId, Long documentId) {
        TaxFilingDocument link = requireLink(filingId, documentId);
        filingDocumentRepository.delete(link);
        documents.delete(link.getDocument());
    }

    /**
     * Borra los documentos de una declaración que se va a borrar. Lo llama
     * {@link TaxFilingService}: sin esto la fila de la declaración no se podría
     * borrar (la clave ajena) y los ficheros se quedarían en el almacén.
     */
    @Transactional
    public void deleteByFiling(Long filingId) {
        List<TaxFilingDocument> links =
                filingDocumentRepository.findByTaxFilingIdOrderByDocumentUploadedAtDescDocumentIdDesc(filingId);
        filingDocumentRepository.deleteAll(links);
        links.forEach(link -> documents.delete(link.getDocument()));
    }

    /** En una declaración sólo cabe lo que la acredita: el justificante o, si acaso, otro fichero. */
    private DocumentType requireFilingType(DocumentType type) {
        if (type == null) return DocumentType.JUSTIFICANTE;
        if (!DocumentType.forTaxFiling().contains(type)) {
            throw new BadRequestException("Un documento de tipo " + type + " no va en una declaración presentada");
        }
        return type;
    }

    private TaxFiling requireFiling(Long filingId) {
        return taxFilingRepository.findById(filingId)
                .orElseThrow(() -> new ResourceNotFoundException("Tax filing not found with id: " + filingId));
    }

    /** Un documento de esa declaración; comprueba primero que la declaración exista. */
    private TaxFilingDocument requireLink(Long filingId, Long documentId) {
        requireFiling(filingId);
        return filingDocumentRepository.findByTaxFilingIdAndDocumentId(filingId, documentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Document " + documentId + " not found for tax filing " + filingId));
    }
}
