package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.dto.RentalDocumentDTO;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.Document;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.RentalDocument;
import com.storagemanager.storage_management.model.enums.DocumentType;
import com.storagemanager.storage_management.repository.RentalAgreementRepository;
import com.storagemanager.storage_management.repository.RentalDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Los documentos de un alquiler: la copia firmada del contrato, sus anexos y las
 * fotos de la entrega. Misma forma que {@link ClientDocumentService}, cambiando
 * la tabla de relación; del fichero se encarga {@link DocumentService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RentalDocumentService {

    private final RentalDocumentRepository rentalDocumentRepository;
    private final RentalAgreementRepository rentalRepository;
    private final DocumentService documents;

    public List<RentalDocumentDTO> getDocuments(Long rentalId) {
        requireRental(rentalId);
        return rentalDocumentRepository
                .findByRentalAgreementIdOrderByDocumentUploadedAtDescDocumentIdDesc(rentalId).stream()
                .map(RentalDocumentDTO::of)
                .toList();
    }

    @Transactional
    public RentalDocumentDTO upload(Long rentalId, MultipartFile file, DocumentType type, String description) {
        RentalAgreement rental = requireRental(rentalId);
        Document document = documents.store(
                "rentals/" + rentalId, file, requireRentalType(type), description);

        try {
            RentalDocument saved = rentalDocumentRepository.save(RentalDocument.builder()
                    .rentalAgreement(rental)
                    .document(document)
                    .build());
            return RentalDocumentDTO.of(saved);
        } catch (RuntimeException e) {
            // Sin relación, el documento no es de nadie: no puede quedarse.
            documents.delete(document);
            throw e;
        }
    }

    public DocumentService.Content download(Long rentalId, Long documentId) {
        return documents.open(requireLink(rentalId, documentId).getDocument());
    }

    @Transactional
    public void delete(Long rentalId, Long documentId) {
        RentalDocument link = requireLink(rentalId, documentId);
        rentalDocumentRepository.delete(link);
        documents.delete(link.getDocument());
    }

    /** En un alquiler sólo cabe lo suyo; sin decir nada, la copia del contrato. */
    private DocumentType requireRentalType(DocumentType type) {
        if (type == null) return DocumentType.CONTRATO_ALQUILER;
        if (!DocumentType.forRental().contains(type)) {
            throw new BadRequestException("Un documento de tipo " + type
                    + " no va en el alquiler, sino en la ficha del cliente");
        }
        return type;
    }

    private RentalAgreement requireRental(Long rentalId) {
        return rentalRepository.findById(rentalId)
                .orElseThrow(() -> new ResourceNotFoundException("Rental agreement not found with id: " + rentalId));
    }

    private RentalDocument requireLink(Long rentalId, Long documentId) {
        return rentalDocumentRepository.findByRentalAgreementIdAndDocumentId(rentalId, documentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Document " + documentId + " not found for rental " + rentalId));
    }
}
