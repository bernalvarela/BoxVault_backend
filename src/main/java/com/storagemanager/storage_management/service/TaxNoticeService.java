package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.dto.ExpenseRequest;
import com.storagemanager.storage_management.dto.TaxNoticeDTO;
import com.storagemanager.storage_management.dto.TaxNoticeDocumentDTO;
import com.storagemanager.storage_management.dto.TaxNoticePaymentRequest;
import com.storagemanager.storage_management.dto.TaxNoticeRequest;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.Document;
import com.storagemanager.storage_management.model.Expense;
import com.storagemanager.storage_management.model.Owner;
import com.storagemanager.storage_management.model.TaxFiling;
import com.storagemanager.storage_management.model.TaxNotice;
import com.storagemanager.storage_management.model.TaxNoticeDocument;
import com.storagemanager.storage_management.model.enums.DocumentType;
import com.storagemanager.storage_management.model.enums.ExpenseCategory;
import com.storagemanager.storage_management.model.enums.TaxModel;
import com.storagemanager.storage_management.model.enums.TaxNoticeKind;
import com.storagemanager.storage_management.model.enums.TaxNoticeStatus;
import com.storagemanager.storage_management.repository.ExpenseRepository;
import com.storagemanager.storage_management.repository.OwnerRepository;
import com.storagemanager.storage_management.repository.TaxFilingRepository;
import com.storagemanager.storage_management.repository.TaxNoticeDocumentRepository;
import com.storagemanager.storage_management.repository.TaxNoticeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Las notificaciones de la AEAT (ver {@link TaxNotice}): su registro, sus
 * documentos y su pago.
 * <p>
 * El pago se apunta como un gasto de categoría {@link ExpenseCategory#SANCIONES},
 * que no es deducible y que el registro del 303 no confunde con una cuota del
 * IVA aunque vaya a la AEAT. Si el cargo ya entró con el extracto del banco (como
 * IMPUESTOS, que es lo que propone el importador para todo lo de la AEAT), se
 * enlaza ese gasto y se le cambia la categoría; si no, se crea.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaxNoticeService {

    private final TaxNoticeRepository noticeRepository;
    private final TaxNoticeDocumentRepository noticeDocumentRepository;
    private final TaxFilingRepository filingRepository;
    private final OwnerRepository ownerRepository;
    private final ExpenseRepository expenseRepository;
    private final ExpenseService expenseService;
    private final DocumentService documents;

    // ---------------------------------------------------------------- Registro

    public List<TaxNoticeDTO> getNotices(TaxModel model) {
        return noticeRepository.findAllByOrderByReceivedOnDescIdDesc().stream()
                .filter(n -> model == null || n.getModel() == model)
                .map(this::toDto)
                .toList();
    }

    public TaxNoticeDTO getNotice(Long id) {
        return toDto(requireNotice(id));
    }

    @Transactional
    public TaxNoticeDTO create(TaxNoticeRequest request) {
        TaxNotice notice = new TaxNotice();
        notice.setStatus(TaxNoticeStatus.PENDIENTE);
        apply(notice, request);
        TaxNotice saved = noticeRepository.save(notice);
        log.info("Notificación de la AEAT registrada: {} {} {} ({})", saved.getKind(), saved.getModel(),
                saved.getYear(), saved.getOwnerName());
        return toDto(saved);
    }

    @Transactional
    public TaxNoticeDTO update(Long id, TaxNoticeRequest request) {
        TaxNotice notice = requireNotice(id);
        apply(notice, request);
        return toDto(noticeRepository.save(notice));
    }

    /** Borra la notificación y sus documentos. El gasto del pago, si lo hay, se queda: el dinero salió. */
    @Transactional
    public void delete(Long id) {
        TaxNotice notice = requireNotice(id);
        List<TaxNoticeDocument> links =
                noticeDocumentRepository.findByTaxNoticeIdOrderByDocumentUploadedAtDescDocumentIdDesc(id);
        noticeDocumentRepository.deleteAll(links);
        noticeDocumentRepository.flush();
        links.forEach(link -> documents.delete(link.getDocument()));
        noticeRepository.delete(notice);
    }

    private void apply(TaxNotice notice, TaxNoticeRequest request) {
        if (request.getModel() == TaxModel.MODELO_303 && request.getYear() != null && request.getQuarter() == null) {
            throw new BadRequestException("En una notificación del 303 indica el trimestre");
        }
        if (request.getResponseDeadline() != null && request.getResponseDeadline().isBefore(request.getReceivedOn())) {
            throw new BadRequestException("El plazo para contestar no puede acabar antes de recibirla");
        }
        if (request.getPaymentDeadline() != null && request.getPaymentDeadline().isBefore(request.getReceivedOn())) {
            throw new BadRequestException("El plazo de pago no puede acabar antes de recibirla");
        }
        if (request.getAmount() != null && request.getReducedAmount() != null
                && request.getReducedAmount().compareTo(request.getAmount()) > 0) {
            throw new BadRequestException("El importe reducido no puede ser mayor que el importe");
        }

        notice.setKind(request.getKind());
        if (request.getStatus() != null) notice.setStatus(request.getStatus());
        notice.setModel(request.getModel());
        notice.setYear(request.getYear());
        notice.setQuarter(request.getModel() == TaxModel.MODELO_303 ? request.getQuarter() : null);

        Owner owner = request.getOwnerId() == null ? null : ownerRepository.findById(request.getOwnerId())
                .orElseThrow(() -> new ResourceNotFoundException("Owner not found with id: " + request.getOwnerId()));
        notice.setOwnerId(owner == null ? null : owner.getId());
        notice.setOwnerName(owner == null ? null : owner.getFullName());

        notice.setFilingId(filingFor(request, owner).map(TaxFiling::getId).orElse(null));
        notice.setReference(trimToNull(request.getReference()));
        notice.setDescription(trimToNull(request.getDescription()));
        notice.setReceivedOn(request.getReceivedOn());
        notice.setResponseDeadline(request.getResponseDeadline());
        notice.setPaymentDeadline(request.getPaymentDeadline());
        notice.setAmount(money(request.getAmount()));
        // La reducción por pronto pago es de las sanciones; en lo demás no hay.
        notice.setReducedAmount(isSanction(request.getKind()) ? money(request.getReducedAmount()) : null);
        notice.setNotes(trimToNull(request.getNotes()));
    }

    /**
     * La declaración de la que trae causa: la que se diga o, si no, la registrada
     * con ese modelo, ejercicio (y trimestre) y titular. Sin declaración registrada
     * se queda sin enlazar, que es justo el caso de no haberla presentado.
     */
    private Optional<TaxFiling> filingFor(TaxNoticeRequest request, Owner owner) {
        if (request.getFilingId() != null) {
            return Optional.of(filingRepository.findById(request.getFilingId())
                    .orElseThrow(() -> new ResourceNotFoundException("Tax filing not found with id: " + request.getFilingId())));
        }
        if (request.getModel() == null || request.getYear() == null) return Optional.empty();
        return filingRepository.findAllByOrderByYearDescQuarterDescFiledDateDescIdDesc().stream()
                .filter(f -> f.getModel() == request.getModel())
                .filter(f -> request.getYear().equals(f.getYear()))
                .filter(f -> request.getModel() != TaxModel.MODELO_303 || Objects.equals(f.getQuarter(), request.getQuarter()))
                .filter(f -> request.getModel() == TaxModel.MODELO_303 || owner == null
                        || Objects.equals(f.getOwnerId(), owner.getId()))
                .findFirst();
    }

    // ---------------------------------------------------------------- Pago

    /**
     * Apunta el pago y la deja como PAGADA. El gasto: el que se indique (que pasa
     * a SANCIONES) o uno nuevo en la unidad indicada.
     */
    @Transactional
    public TaxNoticeDTO pay(Long id, TaxNoticePaymentRequest request) {
        TaxNotice notice = requireNotice(id);
        if (request.getExpenseId() != null && request.getStorageUnitId() != null) {
            throw new BadRequestException("Elige un gasto que ya exista o la unidad para crearlo, no las dos cosas");
        }

        Expense expense = null;
        if (request.getExpenseId() != null) {
            expense = expenseService.getExpenseById(request.getExpenseId());
            if (expense.getCategory() != ExpenseCategory.SANCIONES) {
                log.info("Gasto {} pasa de {} a SANCIONES: paga la notificación {}",
                        expense.getId(), expense.getCategory(), notice.getId());
                expense.setCategory(ExpenseCategory.SANCIONES);
                expense = expenseRepository.save(expense);
            }
        } else if (request.getStorageUnitId() != null) {
            ExpenseRequest expenseRequest = new ExpenseRequest();
            expenseRequest.setAmount(money(request.getPaidAmount()));
            expenseRequest.setCategory(ExpenseCategory.SANCIONES);
            expenseRequest.setExpenseDate(request.getPaidOn());
            expenseRequest.setStorageUnitId(request.getStorageUnitId());
            expenseRequest.setDescription(expenseDescription(notice));
            expense = expenseService.createExpense(expenseRequest);
        }

        notice.setPaidOn(request.getPaidOn());
        notice.setPaidAmount(money(request.getPaidAmount()));
        notice.setExpenseId(expense == null ? notice.getExpenseId() : expense.getId());
        notice.setStatus(TaxNoticeStatus.PAGADA);
        return toDto(noticeRepository.save(notice));
    }

    /** Deshace el pago apuntado (no borra el gasto: eso se hace en Gastos). */
    @Transactional
    public TaxNoticeDTO unpay(Long id) {
        TaxNotice notice = requireNotice(id);
        notice.setPaidOn(null);
        notice.setPaidAmount(null);
        notice.setExpenseId(null);
        notice.setStatus(TaxNoticeStatus.PENDIENTE);
        return toDto(noticeRepository.save(notice));
    }

    /** "Sanción Modelo 184 2024 · ref. 2025GRC..." */
    private static String expenseDescription(TaxNotice notice) {
        StringBuilder text = new StringBuilder(kindLabel(notice.getKind()));
        if (notice.getModel() != null) text.append(' ').append(modelLabel(notice.getModel()));
        if (notice.getQuarter() != null) text.append(' ').append(notice.getQuarter()).append('T');
        if (notice.getYear() != null) text.append(' ').append(notice.getYear());
        if (notice.getReference() != null) text.append(" · ref. ").append(notice.getReference());
        return text.length() > 255 ? text.substring(0, 255) : text.toString();
    }

    private static String kindLabel(TaxNoticeKind kind) {
        return switch (kind) {
            case REQUERIMIENTO -> "Requerimiento AEAT";
            case PROPUESTA_SANCION -> "Propuesta de sanción";
            case SANCION -> "Sanción";
            case RECARGO -> "Recargo";
            case APREMIO -> "Apremio";
            case OTRO -> "Notificación AEAT";
        };
    }

    private static String modelLabel(TaxModel model) {
        return switch (model) {
            case MODELO_303 -> "Modelo 303";
            case MODELO_184 -> "Modelo 184";
            case IRPF -> "IRPF";
        };
    }

    private static boolean isSanction(TaxNoticeKind kind) {
        return kind == TaxNoticeKind.SANCION || kind == TaxNoticeKind.PROPUESTA_SANCION;
    }

    // ---------------------------------------------------------------- Documentos

    public List<TaxNoticeDocumentDTO> getDocuments(Long noticeId) {
        requireNotice(noticeId);
        return noticeDocumentRepository.findByTaxNoticeIdOrderByDocumentUploadedAtDescDocumentIdDesc(noticeId).stream()
                .map(TaxNoticeDocumentDTO::of)
                .toList();
    }

    @Transactional
    public TaxNoticeDocumentDTO upload(Long noticeId, MultipartFile file, DocumentType type, String description) {
        TaxNotice notice = requireNotice(noticeId);
        Document document = documents.store("tax-notices/" + noticeId, file, requireNoticeType(type), description);
        try {
            return TaxNoticeDocumentDTO.of(noticeDocumentRepository.save(TaxNoticeDocument.builder()
                    .taxNotice(notice)
                    .document(document)
                    .build()));
        } catch (RuntimeException e) {
            // Sin relación, el documento no es de nadie: no puede quedarse.
            documents.delete(document);
            throw e;
        }
    }

    public DocumentService.Content download(Long noticeId, Long documentId) {
        return documents.open(requireLink(noticeId, documentId).getDocument());
    }

    @Transactional
    public void deleteDocument(Long noticeId, Long documentId) {
        TaxNoticeDocument link = requireLink(noticeId, documentId);
        noticeDocumentRepository.delete(link);
        documents.delete(link.getDocument());
    }

    /** Sin decir nada, lo que se sube es la notificación. */
    private DocumentType requireNoticeType(DocumentType type) {
        if (type == null) return DocumentType.NOTIFICACION;
        if (!DocumentType.forTaxNotice().contains(type)) {
            throw new BadRequestException("Un documento de tipo " + type + " no va en una notificación de la AEAT");
        }
        return type;
    }

    private TaxNoticeDocument requireLink(Long noticeId, Long documentId) {
        requireNotice(noticeId);
        return noticeDocumentRepository.findByTaxNoticeIdAndDocumentId(noticeId, documentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Document " + documentId + " not found for tax notice " + noticeId));
    }

    // ---------------------------------------------------------------- Comunes

    private TaxNotice requireNotice(Long id) {
        return noticeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tax notice not found with id: " + id));
    }

    private TaxNoticeDTO toDto(TaxNotice n) {
        TaxFiling filing = n.getFilingId() == null ? null : filingRepository.findById(n.getFilingId()).orElse(null);
        Expense expense = n.getExpenseId() == null ? null : expenseRepository.findById(n.getExpenseId()).orElse(null);
        return TaxNoticeDTO.builder()
                .id(n.getId())
                .kind(n.getKind())
                .status(n.getStatus())
                .model(n.getModel())
                .year(n.getYear())
                .quarter(n.getQuarter())
                .ownerId(n.getOwnerId())
                .ownerName(n.getOwnerName())
                .reference(n.getReference())
                .description(n.getDescription())
                .receivedOn(n.getReceivedOn())
                .responseDeadline(n.getResponseDeadline())
                .paymentDeadline(n.getPaymentDeadline())
                .amount(n.getAmount())
                .reducedAmount(n.getReducedAmount())
                .paidOn(n.getPaidOn())
                .paidAmount(n.getPaidAmount())
                .notes(n.getNotes())
                .createdAt(n.getCreatedAt())
                .filingId(filing == null ? null : filing.getId())
                .filingFiledDate(filing == null ? null : filing.getFiledDate())
                .expenseId(expense == null ? null : expense.getId())
                .expenseDate(expense == null ? null : expense.getExpenseDate())
                .expenseAmount(expense == null ? null : expense.getAmount())
                .expenseDescription(expense == null ? null : expense.getDescription())
                .expenseCategory(expense == null ? null : expense.getCategory())
                .documentCount(noticeDocumentRepository.countByTaxNoticeId(n.getId()))
                .build();
    }

    private static java.math.BigDecimal money(java.math.BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
