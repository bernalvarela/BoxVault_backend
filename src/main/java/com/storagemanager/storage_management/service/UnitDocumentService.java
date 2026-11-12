package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.dto.UnitDocumentEntryDTO;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.Document;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.model.UnitDocumentEntry;
import com.storagemanager.storage_management.model.UnitDocumentEntryDocument;
import com.storagemanager.storage_management.model.enums.DocumentType;
import com.storagemanager.storage_management.repository.StorageUnitRepository;
import com.storagemanager.storage_management.repository.UnitDocumentEntryDocumentRepository;
import com.storagemanager.storage_management.repository.UnitDocumentEntryRepository;
import com.storagemanager.storage_management.security.UnitScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Los documentos de una unidad, agrupados en entradas: una entrada lleva un
 * título, una fecha y uno o más ficheros ("Notificación de licencia", con sus
 * tres PDF). Del fichero se encarga {@link DocumentService}; aquí sólo van las
 * entradas y qué ficheros son de cada una. Quien no ve la unidad no ve sus
 * documentos.
 * <p>
 * Los ficheros se suben de uno en uno a una entrada ya creada: así cada
 * petición se queda dentro del límite de subida aunque la entrada lleve varios
 * PDF grandes.
 */
@Service
@RequiredArgsConstructor
public class UnitDocumentService {

    private final UnitDocumentEntryRepository entryRepository;
    private final UnitDocumentEntryDocumentRepository entryDocumentRepository;
    private final StorageUnitRepository storageUnitRepository;
    private final UnitScope unitScope;
    private final DocumentService documents;

    public List<UnitDocumentEntryDTO> getEntries(Long unitId) {
        requireUnit(unitId);
        List<UnitDocumentEntry> entries = entryRepository.findByStorageUnitIdOrderByEntryDateDescIdDesc(unitId);
        if (entries.isEmpty()) return List.of();
        Map<Long, List<UnitDocumentEntryDocument>> byEntry = entryDocumentRepository
                .findByEntryIdInOrderByDocumentUploadedAtAscDocumentIdAsc(
                        entries.stream().map(UnitDocumentEntry::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(link -> link.getEntry().getId()));
        return entries.stream()
                .map(e -> UnitDocumentEntryDTO.of(e, byEntry.getOrDefault(e.getId(), List.of())))
                .toList();
    }

    @Transactional
    public UnitDocumentEntryDTO createEntry(Long unitId, UnitDocumentEntryDTO.Request request) {
        StorageUnit unit = requireUnit(unitId);
        UnitDocumentEntry entry = UnitDocumentEntry.builder().storageUnit(unit).build();
        apply(entry, request);
        return UnitDocumentEntryDTO.of(entryRepository.save(entry), List.of());
    }

    @Transactional
    public UnitDocumentEntryDTO updateEntry(Long unitId, Long entryId, UnitDocumentEntryDTO.Request request) {
        UnitDocumentEntry entry = requireEntry(unitId, entryId);
        apply(entry, request);
        return toDto(entryRepository.save(entry));
    }

    /** Borra la entrada con todos sus ficheros. */
    @Transactional
    public void deleteEntry(Long unitId, Long entryId) {
        UnitDocumentEntry entry = requireEntry(unitId, entryId);
        List<UnitDocumentEntryDocument> links =
                entryDocumentRepository.findByEntryIdOrderByDocumentUploadedAtAscDocumentIdAsc(entryId);
        entryDocumentRepository.deleteAll(links);
        entryDocumentRepository.flush();
        links.forEach(link -> documents.delete(link.getDocument()));
        entryRepository.delete(entry);
    }

    /** Añade un fichero a la entrada y devuelve la entrada entera, ya con él. */
    @Transactional
    public UnitDocumentEntryDTO upload(Long unitId, Long entryId, MultipartFile file,
                                       DocumentType type, String description) {
        UnitDocumentEntry entry = requireEntry(unitId, entryId);
        Document document = documents.store("units/" + unitId + "/entries/" + entryId,
                file, requireUnitType(type), description);
        try {
            entryDocumentRepository.save(UnitDocumentEntryDocument.builder()
                    .entry(entry)
                    .document(document)
                    .build());
        } catch (RuntimeException e) {
            // Sin relación, el documento no es de nadie: no puede quedarse.
            documents.delete(document);
            throw e;
        }
        return toDto(entry);
    }

    public DocumentService.Content download(Long unitId, Long entryId, Long documentId) {
        return documents.open(requireLink(unitId, entryId, documentId).getDocument());
    }

    /**
     * Quita un fichero de la entrada. La entrada se queda aunque se quede sin
     * ficheros (para sustituir uno equivocado); se borra aparte.
     */
    @Transactional
    public UnitDocumentEntryDTO deleteDocument(Long unitId, Long entryId, Long documentId) {
        UnitDocumentEntryDocument link = requireLink(unitId, entryId, documentId);
        entryDocumentRepository.delete(link);
        documents.delete(link.getDocument());
        return toDto(link.getEntry());
    }

    private UnitDocumentEntryDTO toDto(UnitDocumentEntry entry) {
        return UnitDocumentEntryDTO.of(entry,
                entryDocumentRepository.findByEntryIdOrderByDocumentUploadedAtAscDocumentIdAsc(entry.getId()));
    }

    private static void apply(UnitDocumentEntry entry, UnitDocumentEntryDTO.Request request) {
        if (request.getTitle() == null || request.getTitle().isBlank()) {
            throw new BadRequestException("La entrada necesita un título");
        }
        if (request.getEntryDate() == null) {
            throw new BadRequestException("La entrada necesita una fecha");
        }
        entry.setTitle(request.getTitle().trim());
        entry.setEntryDate(request.getEntryDate());
        String notes = request.getNotes() == null ? null : request.getNotes().trim();
        entry.setNotes(notes == null || notes.isEmpty() ? null : notes);
    }

    /** Sin decir nada, el fichero es OTRO. */
    private static DocumentType requireUnitType(DocumentType type) {
        if (type == null) return DocumentType.OTRO;
        if (!DocumentType.forUnit().contains(type)) {
            throw new BadRequestException("Un documento de tipo " + type + " no va en una unidad");
        }
        return type;
    }

    private StorageUnit requireUnit(Long unitId) {
        StorageUnit unit = storageUnitRepository.findById(unitId)
                .orElseThrow(() -> new ResourceNotFoundException("Storage unit not found with id: " + unitId));
        unitScope.requireAccessible(unit);
        return unit;
    }

    private UnitDocumentEntry requireEntry(Long unitId, Long entryId) {
        requireUnit(unitId);
        UnitDocumentEntry entry = entryRepository.findById(entryId)
                .filter(e -> e.getStorageUnit().getId().equals(unitId))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Document entry " + entryId + " not found for unit " + unitId));
        return entry;
    }

    private UnitDocumentEntryDocument requireLink(Long unitId, Long entryId, Long documentId) {
        requireEntry(unitId, entryId);
        return entryDocumentRepository.findByEntryIdAndDocumentId(entryId, documentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Document " + documentId + " not found for entry " + entryId));
    }
}
