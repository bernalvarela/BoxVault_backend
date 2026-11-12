package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.dto.ExpenseDocumentDTO;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.Document;
import com.storagemanager.storage_management.model.Expense;
import com.storagemanager.storage_management.model.ExpenseDocument;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.model.enums.DocumentType;
import com.storagemanager.storage_management.repository.ExpenseDocumentRepository;
import com.storagemanager.storage_management.repository.ExpenseRepository;
import com.storagemanager.storage_management.repository.StorageUnitRepository;
import com.storagemanager.storage_management.repository.UnitDocumentEntryExpenseRepository;
import com.storagemanager.storage_management.security.UnitScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Los documentos archivados en un gasto: el justificante del pago, la factura o
 * el recibo. Como el gasto va contra una unidad, es también la forma de tener
 * junto a la unidad el justificante de lo que se pagó por ella (la tasa de una
 * licencia, el IBI...).
 * <p>
 * Sólo lleva la relación gasto–documento: del fichero se encarga
 * {@link DocumentService}. Quien no ve la unidad del gasto no ve sus documentos.
 * No depende de {@link ExpenseService}, que la llama al borrar un gasto.
 */
@Service
@RequiredArgsConstructor
public class ExpenseDocumentService {

    private final ExpenseDocumentRepository expenseDocumentRepository;
    private final ExpenseRepository expenseRepository;
    private final StorageUnitRepository storageUnitRepository;
    private final UnitDocumentEntryExpenseRepository entryExpenseRepository;
    private final UnitScope unitScope;
    private final DocumentService documents;

    public List<ExpenseDocumentDTO> getDocuments(Long expenseId) {
        requireExpense(expenseId);
        return expenseDocumentRepository.findByExpenseIdOrderByDocumentUploadedAtDescDocumentIdDesc(expenseId).stream()
                .map(ExpenseDocumentDTO::of)
                .toList();
    }

    /**
     * Los documentos de los gastos de una unidad, con los datos del gasto: los
     * que se enseñan en la pestaña de documentos de la unidad. Los de gastos
     * relacionados con una entrada no vienen: salen dentro de la entrada.
     */
    public List<ExpenseDocumentDTO> getDocumentsForUnit(Long unitId) {
        StorageUnit unit = storageUnitRepository.findById(unitId)
                .orElseThrow(() -> new ResourceNotFoundException("Storage unit not found with id: " + unitId));
        unitScope.requireAccessible(unit);
        Set<Long> inEntries = entryExpenseRepository.findByEntryStorageUnitId(unitId).stream()
                .map(link -> link.getExpense().getId())
                .collect(Collectors.toSet());
        return expenseDocumentRepository
                .findByExpenseStorageUnitIdOrderByExpenseExpenseDateDescExpenseIdDescDocumentIdAsc(unitId).stream()
                .filter(link -> !inEntries.contains(link.getExpense().getId()))
                .map(ExpenseDocumentDTO::of)
                .toList();
    }

    /** Cuántos documentos tiene cada gasto que tenga alguno, para marcarlo en el listado. */
    public Map<Long, Long> countByExpense() {
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : expenseDocumentRepository.countByExpense()) {
            counts.put((Long) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    @Transactional
    public ExpenseDocumentDTO upload(Long expenseId, MultipartFile file, DocumentType type, String description) {
        Expense expense = requireExpense(expenseId);
        Document document = documents.store("expenses/" + expenseId, file, requireExpenseType(type), description);
        try {
            return ExpenseDocumentDTO.of(expenseDocumentRepository.save(ExpenseDocument.builder()
                    .expense(expense)
                    .document(document)
                    .build()));
        } catch (RuntimeException e) {
            // Sin relación, el documento no es de nadie: no puede quedarse.
            documents.delete(document);
            throw e;
        }
    }

    public DocumentService.Content download(Long expenseId, Long documentId) {
        return documents.open(requireLink(expenseId, documentId).getDocument());
    }

    @Transactional
    public void delete(Long expenseId, Long documentId) {
        ExpenseDocument link = requireLink(expenseId, documentId);
        expenseDocumentRepository.delete(link);
        documents.delete(link.getDocument());
    }

    /**
     * Borra los documentos de un gasto que se va a borrar, y su relación con una
     * entrada de documentos si la tiene. Lo llama {@link ExpenseService#deleteExpense}:
     * sin esto la fila del gasto no se podría borrar (las claves ajenas) y los
     * ficheros se quedarían en el almacén.
     */
    @Transactional
    public void deleteByExpense(Long expenseId) {
        entryExpenseRepository.findByExpenseId(expenseId).ifPresent(entryExpenseRepository::delete);
        List<ExpenseDocument> links =
                expenseDocumentRepository.findByExpenseIdOrderByDocumentUploadedAtDescDocumentIdDesc(expenseId);
        if (links.isEmpty()) return;
        expenseDocumentRepository.deleteAll(links);
        expenseDocumentRepository.flush();
        links.forEach(link -> documents.delete(link.getDocument()));
    }

    /** Sin decir nada, lo que se sube es el justificante del pago. */
    private DocumentType requireExpenseType(DocumentType type) {
        if (type == null) return DocumentType.JUSTIFICANTE;
        if (!DocumentType.forExpense().contains(type)) {
            throw new BadRequestException("Un documento de tipo " + type + " no va en un gasto");
        }
        return type;
    }

    private Expense requireExpense(Long expenseId) {
        Expense expense = expenseRepository.findById(expenseId)
                .orElseThrow(() -> new ResourceNotFoundException("Expense not found with id: " + expenseId));
        unitScope.requireAccessible(expense.getStorageUnit());
        return expense;
    }

    private ExpenseDocument requireLink(Long expenseId, Long documentId) {
        requireExpense(expenseId);
        return expenseDocumentRepository.findByExpenseIdAndDocumentId(expenseId, documentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Document " + documentId + " not found for expense " + expenseId));
    }
}
