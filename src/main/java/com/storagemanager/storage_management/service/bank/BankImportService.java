package com.storagemanager.storage_management.service.bank;

import com.storagemanager.storage_management.dto.BankDTOs.ApplyResultDTO;
import com.storagemanager.storage_management.dto.BankDTOs.ImportDTO;
import com.storagemanager.storage_management.dto.BankDTOs.ImportRequest;
import com.storagemanager.storage_management.dto.BankDTOs.ImportSummaryDTO;
import com.storagemanager.storage_management.dto.BankDTOs.LineDTO;
import com.storagemanager.storage_management.dto.BankDTOs.LineUpdateRequest;
import com.storagemanager.storage_management.dto.BankDTOs.ProfileDTO;
import com.storagemanager.storage_management.dto.BankDTOs.ProfileRequest;
import com.storagemanager.storage_management.dto.BankDTOs.RuleDTO;
import com.storagemanager.storage_management.dto.BankDTOs.SplitDTO;
import com.storagemanager.storage_management.dto.CommunityDTOs.EntryRequest;
import com.storagemanager.storage_management.dto.ExpenseRequest;
import com.storagemanager.storage_management.dto.PaymentRequest;
import com.storagemanager.storage_management.dto.RecordPaymentRequest;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.BankImport;
import com.storagemanager.storage_management.model.BankImportLine;
import com.storagemanager.storage_management.model.BankImportLineSplit;
import com.storagemanager.storage_management.model.BankImportProfile;
import com.storagemanager.storage_management.model.BankMatchRule;
import com.storagemanager.storage_management.model.BankMatchRuleSplit;
import com.storagemanager.storage_management.model.Payment;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.model.enums.BankLineAction;
import com.storagemanager.storage_management.model.enums.BankLineStatus;
import com.storagemanager.storage_management.model.enums.BankProfileContext;
import com.storagemanager.storage_management.model.enums.PaymentMethod;
import com.storagemanager.storage_management.model.enums.PaymentStatus;
import com.storagemanager.storage_management.repository.BankImportLineRepository;
import com.storagemanager.storage_management.repository.BankImportProfileRepository;
import com.storagemanager.storage_management.repository.BankImportRepository;
import com.storagemanager.storage_management.repository.BankMatchRuleRepository;
import com.storagemanager.storage_management.repository.OwnersCommunityRepository;
import com.storagemanager.storage_management.repository.PaymentRepository;
import com.storagemanager.storage_management.repository.RentalAgreementRepository;
import com.storagemanager.storage_management.repository.StorageUnitRepository;
import com.storagemanager.storage_management.service.CommunityService;
import com.storagemanager.storage_management.service.ExpenseService;
import com.storagemanager.storage_management.service.PaymentService;
import com.storagemanager.storage_management.service.pdf.Pdfs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * La importación de extractos bancarios, de punta a punta: los perfiles de cada
 * cuenta, la subida de un extracto con su propuesta, la revisión fila a fila y
 * la aplicación, que es lo único que crea cobros, gastos y apuntes.
 * <p>
 * Subir no toca nada de lo de verdad: deja las filas con lo que se propone.
 * Aplicar crea cada cosa en su propia transacción, de modo que una fila que
 * falla (un mes ya cobrado, una unidad que se borró) se queda marcada con su
 * error y no impide aplicar las demás.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BankImportService {

    private final BankImportProfileRepository profiles;
    private final BankImportRepository imports;
    private final BankImportLineRepository lines;
    private final BankMatchRuleRepository rules;
    private final RentalAgreementRepository rentals;
    private final StorageUnitRepository units;
    private final OwnersCommunityRepository communities;
    private final PaymentRepository payments;
    private final PaymentService paymentService;
    private final ExpenseService expenseService;
    private final CommunityService communityService;
    private final BankMatcher matcher;
    private final PlatformTransactionManager transactionManager;

    // ================================================================ Perfiles

    public List<ProfileDTO> listProfiles() {
        return profiles.findAllByOrderByNameAsc().stream().map(BankImportService::toDTO).toList();
    }

    @Transactional
    public ProfileDTO createProfile(ProfileRequest request) {
        if (profiles.existsByNameIgnoreCase(request.getName().trim())) {
            throw new BadRequestException("Ya hay un perfil que se llama " + request.getName().trim());
        }
        BankImportProfile profile = new BankImportProfile();
        apply(profile, request);
        return toDTO(profiles.save(profile));
    }

    @Transactional
    public ProfileDTO updateProfile(Long id, ProfileRequest request) {
        BankImportProfile profile = requireProfile(id);
        String name = request.getName().trim();
        if (!profile.getName().equalsIgnoreCase(name) && profiles.existsByNameIgnoreCase(name)) {
            throw new BadRequestException("Ya hay un perfil que se llama " + name);
        }
        apply(profile, request);
        return toDTO(profiles.save(profile));
    }

    @Transactional
    public void deleteProfile(Long id) {
        BankImportProfile profile = requireProfile(id);
        if (imports.existsByProfileId(id)) {
            throw new BadRequestException("El perfil " + profile.getName() + " ya tiene extractos importados: "
                    + "bórralos antes o deja el perfil como está");
        }
        profiles.delete(profile);
    }

    private void apply(BankImportProfile profile, ProfileRequest r) {
        if (r.getAmountColumn() == null && r.getDebitColumn() == null && r.getCreditColumn() == null) {
            throw new BadRequestException("Indica la columna del importe, o las de cargo y abono");
        }
        if (r.getContext() == BankProfileContext.COMUNIDAD && r.getCommunityId() == null) {
            throw new BadRequestException("Una cuenta de la comunidad necesita saber de qué comunidad es");
        }
        profile.setName(r.getName().trim());
        profile.setBankName(trimToNull(r.getBankName()));
        profile.setAccountLabel(trimToNull(r.getAccountLabel()));
        profile.setContext(r.getContext());
        profile.setCommunity(r.getCommunityId() == null ? null : communities.findById(r.getCommunityId())
                .orElseThrow(() -> new ResourceNotFoundException("Community not found with id: " + r.getCommunityId())));
        profile.setDefaultUnit(r.getDefaultUnitId() == null ? null : requireUnit(r.getDefaultUnitId()));
        profile.setEncoding(r.getEncoding() == null || r.getEncoding().isBlank() ? "UTF-8" : r.getEncoding().trim());
        profile.setHeaderRow(r.getHeaderRow() == null ? 1 : r.getHeaderRow());
        profile.setDateColumn(r.getDateColumn());
        profile.setValueDateColumn(r.getValueDateColumn());
        profile.setConceptColumn(r.getConceptColumn());
        profile.setConceptExtraColumn(r.getConceptExtraColumn());
        profile.setAmountColumn(r.getAmountColumn());
        profile.setDebitColumn(r.getAmountColumn() != null ? null : r.getDebitColumn());
        profile.setCreditColumn(r.getAmountColumn() != null ? null : r.getCreditColumn());
        profile.setBalanceColumn(r.getBalanceColumn());
        profile.setReferenceColumn(r.getReferenceColumn());
        profile.setDateFormat(r.getDateFormat() == null || r.getDateFormat().isBlank() ? "dd/MM/yyyy" : r.getDateFormat().trim());
        profile.setDecimalComma(!Boolean.FALSE.equals(r.getDecimalComma()));
    }

    // ================================================================ Importar

    @Transactional(readOnly = true)
    public List<ImportSummaryDTO> listImports() {
        return imports.findAllByOrderByCreatedAtDescIdDesc().stream().map(this::summaryOf).toList();
    }

    // Las filas de un extracto se cargan al pedirlas: estas lecturas necesitan
    // su transacción, que con open-in-view apagado no la pone nadie más.
    @Transactional(readOnly = true)
    public ImportDTO getImport(Long id) {
        return toDTO(requireImport(id), List.of());
    }

    /**
     * Lee el extracto con su perfil, descarta lo que ya estaba importado y deja
     * una propuesta en cada fila. No crea ningún cobro ni gasto.
     */
    @Transactional
    public ImportDTO importStatement(ImportRequest request) {
        BankImportProfile profile = requireProfile(request.getProfileId());
        BankStatementParser.Result parsed = BankStatementParser.parse(profile, request.getRows());
        if (parsed.movements().isEmpty()) {
            throw new BadRequestException("No se ha encontrado ningún movimiento con el perfil «" + profile.getName()
                    + "». Comprueba la fila de la cabecera y las columnas"
                    + (parsed.warnings().isEmpty() ? "" : ": " + String.join("; ", parsed.warnings().subList(0,
                            Math.min(3, parsed.warnings().size())))));
        }

        List<String> fingerprints = parsed.movements().stream()
                .map(m -> BankStatementParser.fingerprint(profile.getId(), m)).toList();
        Set<String> seen = lines.findLiveFingerprints(fingerprints, BankLineStatus.DISCARDED);

        BankImport bankImport = BankImport.builder()
                .profile(profile)
                .fileName(trimToNull(request.getFileName()))
                .build();
        BankMatcher.Context ctx = matcher.load();
        Set<String> inThisFile = new java.util.HashSet<>();
        for (int i = 0; i < parsed.movements().size(); i++) {
            BankStatementParser.Movement m = parsed.movements().get(i);
            String fingerprint = fingerprints.get(i);
            // Repetido en otro extracto, o dos veces en este mismo (un fichero que
            // se solapa consigo mismo al exportar por fechas).
            boolean duplicate = seen.contains(fingerprint) || !inThisFile.add(fingerprint);
            BankImportLine line = BankImportLine.builder()
                    .bankImport(bankImport)
                    .lineNumber(m.lineNumber())
                    .date(m.date())
                    .valueDate(m.valueDate())
                    .concept(m.concept())
                    .amount(m.amount())
                    .balance(m.balance())
                    .fingerprint(fingerprint)
                    .bankReference(m.reference())
                    .duplicate(duplicate)
                    .build();
            bankImport.getLines().add(line);
        }

        // Primera pasada: los ingresos que ya están apuntados a mano y cuyo
        // pagador se reconoce por el nombre. Va antes que nada para que un ingreso
        // sin nombre no se quede con el cobro de otro que paga lo mismo.
        List<BankImportLine> fresh = bankImport.getLines().stream()
                .filter(l -> !Boolean.TRUE.equals(l.getDuplicate())).toList();
        Set<BankImportLine> recorded = new java.util.HashSet<>();
        for (BankImportLine line : fresh) {
            if (matcher.markRecordedByPayer(line, profile, ctx)) recorded.add(line);
        }
        // Segunda pasada: la propuesta de todo lo demás.
        for (BankImportLine line : bankImport.getLines()) {
            if (recorded.contains(line)) continue;
            if (Boolean.TRUE.equals(line.getDuplicate())) {
                // Sin propuesta: no debe quedarse con ningún cobro apuntado a mano.
                line.setAction(BankLineAction.NONE);
                line.setStatus(BankLineStatus.DISCARDED);
                line.setReason("Ya importado en otro extracto: se descarta para no apuntarlo dos veces");
                continue;
            }
            matcher.propose(line, profile, ctx);
        }
        BankImport saved = imports.save(bankImport);
        log.info("Importado el extracto {} con el perfil {}: {} movimientos ({} duplicados)",
                saved.getFileName(), profile.getName(), saved.getLines().size(),
                saved.getLines().stream().filter(l -> Boolean.TRUE.equals(l.getDuplicate())).count());
        return toDTO(saved, parsed.warnings());
    }

    /** Borra un extracto que todavía no ha creado nada. */
    @Transactional
    public void deleteImport(Long id) {
        BankImport bankImport = requireImport(id);
        if (bankImport.getLines().stream().anyMatch(l -> l.getStatus() == BankLineStatus.APPLIED)) {
            throw new BadRequestException("Este extracto ya creó cobros o gastos: no se puede borrar. "
                    + "Descarta las filas que queden pendientes.");
        }
        imports.delete(bankImport);
    }

    // ================================================================ Revisar

    /** Lo que cambia quien revisa en una fila. Se vuelve a calcular su resumen. */
    @Transactional
    public LineDTO updateLine(Long importId, Long lineId, LineUpdateRequest request) {
        BankImportLine line = requireLine(importId, lineId);
        if (line.getStatus() == BankLineStatus.APPLIED) {
            throw new BadRequestException("Esta fila ya está aplicada: lo que creó se corrige en su pantalla");
        }
        if (request.getStatus() == BankLineStatus.DISCARDED) {
            line.setStatus(BankLineStatus.DISCARDED);
            return toDTO(lines.save(line));
        }
        line.setStatus(BankLineStatus.PENDING);
        line.setError(null);
        // Recuperar una fila que se dio por "ya registrada" es decir que no lo
        // estaba: deja de apuntar a aquel cobro y se apuntará como uno nuevo.
        if (Boolean.TRUE.equals(line.getAlreadyRecorded())) {
            line.setAlreadyRecorded(false);
            line.setPaymentId(null);
        }

        if (request.getAction() != null) {
            BankLineAction action = request.getAction();
            if (action == BankLineAction.RENT_PAYMENT && !line.isIncome()) {
                throw new BadRequestException("Un cargo no puede ser el cobro de una mensualidad");
            }
            if (action == BankLineAction.EXPENSE && line.isIncome()) {
                throw new BadRequestException("Un ingreso no puede ser un gasto");
            }
            if (action == BankLineAction.COMMUNITY_ENTRY && request.getCommunityEntryType() != null
                    && request.getCommunityEntryType().isIncome() != line.isIncome()) {
                throw new BadRequestException(line.isIncome()
                        ? "Un ingreso en la comunidad es una cuota, una derrama u otro ingreso"
                        : "Un cargo en la comunidad es un gasto u otro gasto");
            }
            line.setAction(action);
            line.setRentalAgreement(action == BankLineAction.RENT_PAYMENT && request.getRentalAgreementId() != null
                    ? rentals.findById(request.getRentalAgreementId())
                        .orElseThrow(() -> new ResourceNotFoundException("Rental agreement not found"))
                    : null);
            line.setPeriodYear(action == BankLineAction.RENT_PAYMENT ? request.getPeriodYear() : null);
            line.setPeriodMonth(action == BankLineAction.RENT_PAYMENT ? request.getPeriodMonth() : null);
            line.setPeriodCount(action == BankLineAction.RENT_PAYMENT && request.getPeriodCount() != null
                    ? Math.max(1, Math.min(12, request.getPeriodCount())) : 1);
            line.setExpenseCategory(action == BankLineAction.EXPENSE ? request.getExpenseCategory() : null);
            line.setCommunityEntryType(action == BankLineAction.COMMUNITY_ENTRY ? request.getCommunityEntryType() : null);
            line.setStorageUnit((action == BankLineAction.EXPENSE || action == BankLineAction.COMMUNITY_ENTRY)
                    && request.getStorageUnitId() != null ? requireUnit(request.getStorageUnitId()) : null);
            line.getSplits().clear();
            // Un gasto o un apunte de la comunidad pueden ser de varias unidades a la
            // vez: la cuota de los dos pisos más la parte de los bajos.
            if ((action == BankLineAction.EXPENSE || action == BankLineAction.COMMUNITY_ENTRY)
                    && request.getSplits() != null && request.getSplits().size() >= 2) {
                applySplits(line, request.getSplits());
            }
            line.setReason("Asignado a mano");
            line.setLearn(!Boolean.FALSE.equals(request.getLearn()));
        }
        return toDTO(lines.save(line));
    }

    /**
     * Reparte un cargo entre varias unidades: una parte por unidad, cada una en
     * positivo y sin repetir unidad, que sumen exactamente el cargo.
     */
    private void applySplits(BankImportLine line, List<SplitDTO> parts) {
        BigDecimal total = line.getAmount().abs();
        BigDecimal sum = BigDecimal.ZERO;
        Set<Long> seen = new java.util.HashSet<>();
        int position = 0;
        for (SplitDTO part : parts) {
            if (part.getStorageUnitId() == null || part.getAmount() == null || part.getAmount().signum() <= 0) {
                throw new BadRequestException("Cada parte del reparto necesita su unidad y un importe mayor que cero");
            }
            if (!seen.add(part.getStorageUnitId())) {
                throw new BadRequestException("Una unidad sale dos veces en el reparto: junta sus partes");
            }
            BigDecimal amount = part.getAmount().setScale(2, java.math.RoundingMode.HALF_UP);
            sum = sum.add(amount);
            line.getSplits().add(BankImportLineSplit.builder()
                    .line(line).storageUnit(requireUnit(part.getStorageUnitId())).amount(amount).position(position++)
                    .build());
        }
        if (sum.compareTo(total) != 0) {
            throw new BadRequestException("Las partes suman " + Pdfs.euros(sum) + " y el cargo es de " + Pdfs.euros(total)
                    + ": tienen que coincidir");
        }
        line.setStorageUnit(null);
    }

    /** Si el reparto de la fila es válido: dos partes o más que suman el cargo. */
    private static boolean splitsComplete(BankImportLine line) {
        if (line.getSplits() == null || line.getSplits().size() < 2) return false;
        BigDecimal sum = line.getSplits().stream().map(BankImportLineSplit::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.compareTo(line.getAmount().abs()) == 0;
    }

    // ================================================================ Aplicar

    /**
     * Crea lo que dicen las filas pendientes y completas, cada una en su propia
     * transacción. Las que no dicen nada (acción "nada") se descartan; las
     * incompletas se quedan pendientes para terminarlas.
     */
    public ApplyResultDTO applyImport(Long importId) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        long payments = 0, expenses = 0, entries = 0, discarded = 0, failed = 0, incomplete = 0, learned = 0;

        List<Long> pending = tx.execute(status -> requireImport(importId).getLines().stream()
                .filter(l -> l.getStatus() == BankLineStatus.PENDING || l.getStatus() == BankLineStatus.FAILED)
                .map(BankImportLine::getId)
                .toList());

        for (Long lineId : pending) {
            try {
                Outcome outcome = tx.execute(status -> applyLine(lineId));
                if (outcome == null) continue;
                switch (outcome.kind()) {
                    case "payment" -> payments++;
                    case "expense" -> expenses++;
                    case "entry" -> entries++;
                    case "discarded" -> discarded++;
                    case "incomplete" -> incomplete++;
                    default -> { }
                }
                if (outcome.learned()) learned++;
            } catch (RuntimeException e) {
                failed++;
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                tx.executeWithoutResult(status -> lines.findById(lineId).ifPresent(l -> {
                    l.setStatus(BankLineStatus.FAILED);
                    l.setError(message.length() > 500 ? message.substring(0, 500) : message);
                    lines.save(l);
                }));
                log.warn("No se pudo aplicar la fila {} del extracto {}: {}", lineId, importId, message);
            }
        }

        tx.executeWithoutResult(status -> imports.findById(importId).ifPresent(i -> {
            boolean open = i.getLines().stream().anyMatch(l -> l.getStatus() == BankLineStatus.PENDING
                    || l.getStatus() == BankLineStatus.FAILED);
            i.setStatus(open ? BankImport.REVIEW : BankImport.APPLIED);
            imports.save(i);
        }));

        log.info("Aplicado el extracto {}: {} cobros, {} gastos, {} apuntes, {} descartados, {} fallidos, {} incompletos",
                importId, payments, expenses, entries, discarded, failed, incomplete);
        return ApplyResultDTO.builder()
                .payments(payments).expenses(expenses).communityEntries(entries)
                .discarded(discarded).failed(failed).skippedIncomplete(incomplete).rulesLearned(learned)
                // Por la plantilla y no por getImport: llamado desde aquí no
                // pasaría por el proxy y no tendría transacción.
                .bankImport(tx.execute(status -> toDTO(requireImport(importId), List.of())))
                .build();
    }

    private record Outcome(String kind, boolean learned) {}

    private Outcome applyLine(Long lineId) {
        BankImportLine line = lines.findById(lineId).orElse(null);
        if (line == null) return null;
        if (line.getAction() == BankLineAction.NONE) {
            line.setStatus(BankLineStatus.DISCARDED);
            boolean learned = learn(line);
            lines.save(line);
            return new Outcome("discarded", learned);
        }
        if (!isComplete(line)) return new Outcome("incomplete", false);

        BankImportProfile profile = line.getBankImport().getProfile();
        String kind;
        switch (line.getAction()) {
            case RENT_PAYMENT -> {
                line.setPaymentId(collect(line).getId());
                kind = "payment";
            }
            case EXPENSE -> {
                if (splitsComplete(line)) {
                    // Un gasto por unidad, con su parte: lo deducible es de cada una.
                    Long first = null;
                    for (BankImportLineSplit split : line.getSplits()) {
                        Long id = createExpense(line, split.getAmount(), split.getStorageUnit().getId(),
                                " (parte de " + Pdfs.euros(line.getAmount().abs()) + ")");
                        if (first == null) first = id;
                    }
                    line.setExpenseId(first);
                } else {
                    line.setExpenseId(createExpense(line, line.getAmount().abs(), line.getStorageUnit().getId(), ""));
                }
                kind = "expense";
            }
            case COMMUNITY_ENTRY -> {
                if (profile.getCommunity() == null) {
                    throw new BadRequestException("El perfil " + profile.getName()
                            + " no es de ninguna comunidad: un apunte de la comunidad no tiene dónde ir");
                }
                if (splitsComplete(line)) {
                    // Un apunte por unidad: el estado de cuentas de la comunidad dice qué
                    // debe cada una, y una cuota de dos pisos son dos cuotas.
                    Long first = null;
                    for (BankImportLineSplit split : line.getSplits()) {
                        Long id = addCommunityEntry(line, profile, split.getAmount(), split.getStorageUnit().getId());
                        if (first == null) first = id;
                    }
                    line.setCommunityEntryId(first);
                } else {
                    line.setCommunityEntryId(addCommunityEntry(line, profile, line.getAmount().abs(),
                            line.getStorageUnit() == null ? null : line.getStorageUnit().getId()));
                }
                kind = "entry";
            }
            default -> throw new IllegalStateException("Acción desconocida: " + line.getAction());
        }
        line.setStatus(BankLineStatus.APPLIED);
        line.setError(null);
        boolean learned = learn(line);
        lines.save(line);
        return new Outcome(kind, learned);
    }

    private Long addCommunityEntry(BankImportLine line, BankImportProfile profile, BigDecimal amount, Long unitId) {
        EntryRequest request = new EntryRequest();
        request.setEntryDate(line.getDate());
        request.setType(line.getCommunityEntryType());
        request.setConcept(shorten(line.getConcept(), 200));
        request.setAmount(amount);
        request.setStorageUnitId(unitId);
        if (!line.getCommunityEntryType().isIncome()) request.setSupplier(shorten(line.getConcept(), 150));
        request.setNotes("Importado del banco (" + profile.getName() + ")");
        return communityService.addEntry(profile.getCommunity().getId(), request).getId();
    }

    private Long createExpense(BankImportLine line, BigDecimal amount, Long unitId, String note) {
        ExpenseRequest request = new ExpenseRequest();
        request.setAmount(amount);
        request.setDescription(shorten(line.getConcept() + note, 255));
        request.setCategory(line.getExpenseCategory());
        request.setExpenseDate(line.getDate());
        request.setStorageUnitId(unitId);
        return expenseService.createExpense(request).getId();
    }

    /**
     * Apunta el cobro de una o varias mensualidades seguidas: "septiembre y
     * octubre" por 110 € son dos cobros de 55 €. Cada mes se reparte la
     * mensualidad del contrato y el último se lleva lo que quede. Devuelve el
     * cobro del primer mes, que es el que queda enlazado en la fila.
     */
    private Payment collect(BankImportLine line) {
        RentalAgreement rental = line.getRentalAgreement();
        int count = line.getPeriodCount() == null || line.getPeriodCount() < 1 ? 1 : line.getPeriodCount();
        YearMonth start = YearMonth.of(line.getPeriodYear(), line.getPeriodMonth());
        BigDecimal charge = rental.getMonthlyCharge();
        BigDecimal left = line.getAmount();

        Payment first = null;
        for (int i = 0; i < count; i++) {
            YearMonth ym = start.plusMonths(i);
            BigDecimal amount = i == count - 1 ? left : charge.min(left);
            if (amount.signum() <= 0) break;
            Payment payment = collectMonth(rental, ym, amount, line);
            if (first == null) first = payment;
            left = left.subtract(amount);
        }
        return first;
    }

    /**
     * Un mes: si ya tenía fila de cobro (un pago parcial anterior, una
     * corrección), se le suma; si no, se crea con lo que dice el contrato como
     * importe debido.
     */
    private Payment collectMonth(RentalAgreement rental, YearMonth ym, BigDecimal amount, BankImportLine line) {
        int year = ym.getYear();
        int month = ym.getMonthValue();
        String reference = shorten("Banco · " + line.getConcept(), 100);

        Payment existing = payments.findByRentalAgreementIdAndBillingPeriodYearAndBillingPeriodMonth(
                rental.getId(), year, month).orElse(null);
        if (existing != null) {
            if (existing.getStatus() == PaymentStatus.CANCELLED) {
                throw new BadRequestException(Pdfs.monthOf(year, month) + " está marcado como no cobrable en "
                        + rental.getAgreementNumber() + ": elige otro mes");
            }
            RecordPaymentRequest record = new RecordPaymentRequest();
            BigDecimal before = existing.getAmountPaid() == null ? BigDecimal.ZERO : existing.getAmountPaid();
            record.setAmountPaid(before.add(amount));
            record.setPaymentMethod(PaymentMethod.BANK_TRANSFER);
            record.setPaymentDate(line.getDate());
            record.setTransactionReference(reference);
            return paymentService.recordPayment(existing.getId(), record);
        }

        PaymentRequest request = new PaymentRequest();
        request.setRentalAgreementId(rental.getId());
        request.setBillingPeriodYear(year);
        request.setBillingPeriodMonth(month);
        request.setAmountDue(rental.getMonthlyCharge());
        request.setAmountPaid(amount);
        int day = rental.getBillingDayOfMonth() == null ? 1 : Math.min(rental.getBillingDayOfMonth(), ym.lengthOfMonth());
        request.setDueDate(ym.atDay(day));
        request.setPaymentDate(line.getDate());
        request.setStatus(PaymentStatus.PAID);
        request.setPaymentMethod(PaymentMethod.BANK_TRANSFER);
        request.setTransactionReference(reference);
        return paymentService.createPayment(request);
    }

    /**
     * Si una persona asignó la fila a mano, lo recuerda para los próximos
     * extractos. Una regla igual que ya exista se sustituye: manda la última
     * corrección.
     */
    private boolean learn(BankImportLine line) {
        if (!Boolean.TRUE.equals(line.getLearn())) return false;
        String pattern = TextMatch.learnKey(line.getConcept());
        if (pattern.isBlank()) return false;

        BankMatchRule rule = rules.findFirstByPattern(pattern).orElseGet(BankMatchRule::new);
        rule.setPattern(pattern);
        rule.setAction(line.getAction());
        rule.setClient(line.getAction() == BankLineAction.RENT_PAYMENT && line.getRentalAgreement() != null
                ? BankMatcher.payerOf(line.getRentalAgreement()) : null);
        rule.setExpenseCategory(line.getAction() == BankLineAction.EXPENSE ? line.getExpenseCategory() : null);
        rule.setCommunityEntryType(line.getAction() == BankLineAction.COMMUNITY_ENTRY ? line.getCommunityEntryType() : null);
        rule.setStorageUnit(line.getAction() == BankLineAction.EXPENSE || line.getAction() == BankLineAction.COMMUNITY_ENTRY
                ? line.getStorageUnit() : null);
        // El reparto se guarda como proporción, para que valga si la cuota cambia.
        rule.getSplits().clear();
        if ((line.getAction() == BankLineAction.EXPENSE || line.getAction() == BankLineAction.COMMUNITY_ENTRY)
                && splitsComplete(line)) {
            BigDecimal total = line.getAmount().abs();
            int position = 0;
            for (BankImportLineSplit split : line.getSplits()) {
                rule.getSplits().add(BankMatchRuleSplit.builder()
                        .rule(rule).storageUnit(split.getStorageUnit())
                        .share(split.getAmount().divide(total, 6, java.math.RoundingMode.HALF_UP))
                        .position(position++)
                        .build());
            }
            rule.setStorageUnit(null);
        }
        rules.save(rule);
        line.setLearn(false);
        return true;
    }

    /** Si a la fila no le falta nada para aplicarse. */
    static boolean isComplete(BankImportLine line) {
        return switch (line.getAction()) {
            case RENT_PAYMENT -> line.getRentalAgreement() != null && line.getPeriodYear() != null
                    && line.getPeriodMonth() != null && line.isIncome();
            case EXPENSE -> line.getExpenseCategory() != null && !line.isIncome()
                    && (line.getStorageUnit() != null || splitsComplete(line));
            // La unidad es opcional (un gasto del edificio no tiene), pero un reparto
            // empezado tiene que cuadrar.
            case COMMUNITY_ENTRY -> line.getCommunityEntryType() != null
                    && line.getCommunityEntryType().isIncome() == line.isIncome()
                    && (line.getSplits().isEmpty() || splitsComplete(line));
            case NONE -> true;
        };
    }

    // ================================================================ Reglas

    public List<RuleDTO> listRules() {
        return rules.findAllByOrderByCreatedAtDescIdDesc().stream().map(r -> RuleDTO.builder()
                .id(r.getId())
                .pattern(r.getPattern())
                .action(r.getAction())
                .clientId(r.getClient() == null ? null : r.getClient().getId())
                .clientName(r.getClient() == null ? null : r.getClient().getFullName())
                .expenseCategory(r.getExpenseCategory())
                .storageUnitId(r.getStorageUnit() == null ? null : r.getStorageUnit().getId())
                .unitLabel(r.getStorageUnit() == null ? null : r.getStorageUnit().getName())
                .communityEntryType(r.getCommunityEntryType())
                .splitsText(r.getSplits() == null || r.getSplits().isEmpty() ? null : r.getSplits().stream()
                        .map(s -> s.getStorageUnit().getName() + " "
                                + s.getShare().multiply(BigDecimal.valueOf(100)).setScale(1, java.math.RoundingMode.HALF_UP)
                                    .stripTrailingZeros().toPlainString() + " %")
                        .collect(Collectors.joining(" · ")))
                .createdAt(r.getCreatedAt())
                .createdBy(r.getCreatedBy())
                .build()).toList();
    }

    @Transactional
    public void deleteRule(Long id) {
        rules.delete(rules.findById(id).orElseThrow(() -> new ResourceNotFoundException("Rule not found with id: " + id)));
    }

    // ================================================================ DTO

    private ImportDTO toDTO(BankImport bankImport, List<String> warnings) {
        return ImportDTO.builder()
                .summary(summaryOf(bankImport))
                .profile(toDTO(bankImport.getProfile()))
                .lines(bankImport.getLines().stream()
                        .sorted(Comparator.comparing(BankImportLine::getLineNumber))
                        .map(this::toDTO).toList())
                .warnings(warnings)
                .build();
    }

    private ImportSummaryDTO summaryOf(BankImport i) {
        List<BankImportLine> all = i.getLines();
        return ImportSummaryDTO.builder()
                .id(i.getId())
                .profileId(i.getProfile().getId())
                .profileName(i.getProfile().getName())
                .fileName(i.getFileName())
                .status(i.getStatus())
                .createdAt(i.getCreatedAt())
                .createdBy(i.getCreatedBy())
                .firstDate(all.stream().map(BankImportLine::getDate).min(LocalDate::compareTo).orElse(null))
                .lastDate(all.stream().map(BankImportLine::getDate).max(LocalDate::compareTo).orElse(null))
                .total(all.size())
                .pending(count(all, BankLineStatus.PENDING))
                .discarded(count(all, BankLineStatus.DISCARDED))
                .applied(count(all, BankLineStatus.APPLIED))
                .failed(count(all, BankLineStatus.FAILED))
                .incomplete(all.stream().filter(l -> l.getStatus() == BankLineStatus.PENDING && !isComplete(l)).count())
                .alreadyRecorded(all.stream().filter(l -> Boolean.TRUE.equals(l.getAlreadyRecorded())).count())
                .duplicates(all.stream().filter(l -> Boolean.TRUE.equals(l.getDuplicate())).count())
                .build();
    }

    private static long count(List<BankImportLine> all, BankLineStatus status) {
        return all.stream().filter(l -> l.getStatus() == status).count();
    }

    private LineDTO toDTO(BankImportLine l) {
        RentalAgreement rental = l.getRentalAgreement();
        StorageUnit unit = l.getStorageUnit();
        return LineDTO.builder()
                .id(l.getId())
                .lineNumber(l.getLineNumber())
                .date(l.getDate())
                .valueDate(l.getValueDate())
                .concept(l.getConcept())
                .amount(l.getAmount())
                .balance(l.getBalance())
                .duplicate(Boolean.TRUE.equals(l.getDuplicate()))
                .alreadyRecorded(Boolean.TRUE.equals(l.getAlreadyRecorded()))
                .status(l.getStatus())
                .action(l.getAction())
                .reason(l.getReason())
                .rentalAgreementId(rental == null ? null : rental.getId())
                .rentalLabel(rental == null ? null : rentalLabel(rental))
                .periodYear(l.getPeriodYear())
                .periodMonth(l.getPeriodMonth())
                .periodCount(l.getPeriodCount() == null ? 1 : l.getPeriodCount())
                .bankReference(l.getBankReference())
                .expenseCategory(l.getExpenseCategory())
                .storageUnitId(unit == null ? null : unit.getId())
                .unitLabel(unit == null ? null : unit.getName())
                .splits(l.getSplits().stream().map(sp -> SplitDTO.builder()
                        .storageUnitId(sp.getStorageUnit().getId())
                        .unitLabel(sp.getStorageUnit().getName())
                        .amount(sp.getAmount())
                        .build()).toList())
                .communityEntryType(l.getCommunityEntryType())
                .learn(Boolean.TRUE.equals(l.getLearn()))
                .paymentId(l.getPaymentId())
                .expenseId(l.getExpenseId())
                .communityEntryId(l.getCommunityEntryId())
                .error(l.getError())
                .complete(isComplete(l))
                .outcome(outcomeOf(l))
                .build();
    }

    /** "CON-2026-03 · Trastero 3 · Ana Gómez Pérez" */
    static String rentalLabel(RentalAgreement rental) {
        List<String> parts = new ArrayList<>();
        parts.add(rental.getAgreementNumber());
        if (rental.getStorageUnit() != null) parts.add(rental.getStorageUnit().getName());
        String names = rental.tenantNames();
        if (!names.isBlank()) parts.add(names);
        return parts.stream().filter(Objects::nonNull).collect(Collectors.joining(" · "));
    }

    /** "octubre de 2026", o "septiembre y octubre de 2026", o "3 meses desde julio de 2026". */
    private static String periodsText(BankImportLine l) {
        if (l.getPeriodYear() == null || l.getPeriodMonth() == null) return "¿qué mes?";
        int count = l.getPeriodCount() == null ? 1 : l.getPeriodCount();
        YearMonth start = YearMonth.of(l.getPeriodYear(), l.getPeriodMonth());
        String first = Pdfs.monthOf(start.getYear(), start.getMonthValue()).toLowerCase();
        if (count <= 1) return first;
        YearMonth last = start.plusMonths(count - 1);
        if (count == 2) {
            String a = first.substring(0, first.indexOf(" de "));
            String b = Pdfs.monthOf(last.getYear(), last.getMonthValue()).toLowerCase();
            return start.getYear() == last.getYear() ? a + " y " + b : first + " y " + b;
        }
        return count + " meses desde " + first;
    }

    /** Lo que va a crear la fila, o lo que creó, en una frase. */
    private static String outcomeOf(BankImportLine l) {
        if (Boolean.TRUE.equals(l.getAlreadyRecorded()) && l.getRentalAgreement() != null) {
            return "Ya existe: cobro de " + (l.getPeriodYear() == null ? "?"
                    : Pdfs.monthOf(l.getPeriodYear(), l.getPeriodMonth()).toLowerCase())
                    + " · " + rentalLabel(l.getRentalAgreement());
        }
        String verb = l.getStatus() == BankLineStatus.APPLIED ? "Creado: " : "";
        return switch (l.getAction()) {
            case RENT_PAYMENT -> l.getRentalAgreement() == null
                    ? "Cobro de una mensualidad: falta el contrato"
                    : verb + "Cobro de " + periodsText(l) + " · " + rentalLabel(l.getRentalAgreement());
            case EXPENSE -> verb + "Gasto · " + (l.getExpenseCategory() == null ? "¿categoría?"
                    : l.getExpenseCategory().name().toLowerCase().replace('_', ' '))
                    + " · " + (!l.getSplits().isEmpty()
                        ? "repartido: " + l.getSplits().stream()
                            .map(sp -> sp.getStorageUnit().getName() + " " + Pdfs.euros(sp.getAmount()))
                            .collect(Collectors.joining(" + "))
                        : l.getStorageUnit() == null ? "falta la unidad" : l.getStorageUnit().getName());
            case COMMUNITY_ENTRY -> verb + "Comunidad · " + (l.getCommunityEntryType() == null ? "¿tipo?"
                    : l.getCommunityEntryType().name().toLowerCase().replace('_', ' '))
                    + (!l.getSplits().isEmpty()
                        ? " · repartido: " + l.getSplits().stream()
                            .map(sp -> sp.getStorageUnit().getName() + " " + Pdfs.euros(sp.getAmount()))
                            .collect(Collectors.joining(" + "))
                        : l.getStorageUnit() == null ? "" : " · " + l.getStorageUnit().getName());
            case NONE -> "No se apunta nada";
        };
    }

    static ProfileDTO toDTO(BankImportProfile p) {
        return ProfileDTO.builder()
                .id(p.getId())
                .name(p.getName())
                .bankName(p.getBankName())
                .accountLabel(p.getAccountLabel())
                .context(p.getContext())
                .communityId(p.getCommunity() == null ? null : p.getCommunity().getId())
                .communityName(p.getCommunity() == null ? null : p.getCommunity().getName())
                .defaultUnitId(p.getDefaultUnit() == null ? null : p.getDefaultUnit().getId())
                .defaultUnitName(p.getDefaultUnit() == null ? null : p.getDefaultUnit().getName())
                .encoding(p.getEncoding())
                .headerRow(p.getHeaderRow())
                .dateColumn(p.getDateColumn())
                .valueDateColumn(p.getValueDateColumn())
                .conceptColumn(p.getConceptColumn())
                .conceptExtraColumn(p.getConceptExtraColumn())
                .amountColumn(p.getAmountColumn())
                .debitColumn(p.getDebitColumn())
                .creditColumn(p.getCreditColumn())
                .balanceColumn(p.getBalanceColumn())
                .referenceColumn(p.getReferenceColumn())
                .dateFormat(p.getDateFormat())
                .decimalComma(p.getDecimalComma())
                .build();
    }

    // ================================================================ Apoyo

    private BankImportProfile requireProfile(Long id) {
        return profiles.findById(id).orElseThrow(() -> new ResourceNotFoundException("Bank profile not found with id: " + id));
    }

    private BankImport requireImport(Long id) {
        return imports.findById(id).orElseThrow(() -> new ResourceNotFoundException("Bank import not found with id: " + id));
    }

    private BankImportLine requireLine(Long importId, Long lineId) {
        BankImportLine line = lines.findById(lineId)
                .orElseThrow(() -> new ResourceNotFoundException("Bank import line not found with id: " + lineId));
        if (!line.getBankImport().getId().equals(importId)) {
            throw new BadRequestException("Esa fila no es de este extracto");
        }
        return line;
    }

    private StorageUnit requireUnit(Long id) {
        return units.findById(id).orElseThrow(() -> new ResourceNotFoundException("Storage unit not found with id: " + id));
    }

    private static String shorten(String text, int max) {
        if (text == null) return null;
        return text.length() > max ? text.substring(0, max) : text;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
