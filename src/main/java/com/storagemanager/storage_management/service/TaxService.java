package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.config.VatUtils;
import com.storagemanager.storage_management.config.VatUtils.Breakdown;
import com.storagemanager.storage_management.dto.IrpfReportDTO;
import com.storagemanager.storage_management.dto.Modelo184DTO;
import com.storagemanager.storage_management.dto.Modelo303DTO;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.Expense;
import com.storagemanager.storage_management.model.Owner;
import com.storagemanager.storage_management.model.OwnerMembership;
import com.storagemanager.storage_management.model.Ownership;
import com.storagemanager.storage_management.model.Payment;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.model.enums.ExpenseCategory;
import com.storagemanager.storage_management.model.enums.OwnerType;
import com.storagemanager.storage_management.model.enums.PaymentStatus;
import com.storagemanager.storage_management.repository.ExpenseRepository;
import com.storagemanager.storage_management.repository.OwnerMembershipRepository;
import com.storagemanager.storage_management.repository.OwnerRepository;
import com.storagemanager.storage_management.repository.OwnershipRepository;
import com.storagemanager.storage_management.repository.PaymentRepository;
import com.storagemanager.storage_management.repository.StorageUnitRepository;
import com.storagemanager.storage_management.security.UnitScope;
import com.storagemanager.storage_management.service.OwnershipService.Effective;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Tax helper reports (Modelo 303, Modelo 184 and IRPF). They are read-only views
 * over payments, expenses, owners' shares and entity memberships; see each DTO for
 * the exact rules. Income always follows the billing period of the mensualidad and
 * counts what was actually paid (PAID payments, {@code amountPaid}), the same basis
 * as the dashboard's "cobrado".
 * <p>
 * The Modelo 303 and the Modelo 184 are filed by a comunidad de bienes (an
 * {@link OwnerType#COMUNIDAD_DE_BIENES} owner) for the units it holds; the IRPF is
 * for persons: their directly-held units plus what each entity attributes to them.
 */
@Service
@RequiredArgsConstructor
public class TaxService {

    /** Expense categories treated as deductible costs of the rental activity (IRPF). */
    public static final Set<ExpenseCategory> DEDUCTIBLE_CATEGORIES = EnumSet.of(
            ExpenseCategory.TRIBUTOS,
            ExpenseCategory.SUMINISTROS,
            ExpenseCategory.REPARACIONES,
            ExpenseCategory.SEGUROS,
            ExpenseCategory.COMUNIDAD,
            ExpenseCategory.OTROS);

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final PaymentRepository paymentRepository;
    private final ExpenseRepository expenseRepository;
    private final StorageUnitRepository storageUnitRepository;
    private final OwnerRepository ownerRepository;
    private final OwnershipRepository ownershipRepository;
    private final OwnerMembershipRepository ownerMembershipRepository;
    private final UnitScope unitScope;

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    private static BigDecimal money(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal zero() {
        return money(BigDecimal.ZERO);
    }

    /** {@code percent} % of an amount, rounded to cents. */
    private static BigDecimal part(BigDecimal amount, BigDecimal percent) {
        return money(amount).multiply(percent).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    private static Breakdown part(Breakdown b, BigDecimal percent) {
        return new Breakdown(part(b.total(), percent), part(b.base(), percent), part(b.vat(), percent));
    }

    private static String parentNumber(StorageUnit unit) {
        return unit.getParent() != null ? unit.getParent().getUnitNumber() : null;
    }

    private static final Comparator<StorageUnit> UNIT_ORDER = Comparator
            .comparing((StorageUnit u) -> u.getRoot().unitNumber().length())
            .thenComparing(u -> u.getRoot().unitNumber(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(u -> u.getParent() == null ? 0 : 1)
            .thenComparing(u -> u.getUnitNumber().length())
            .thenComparing(StorageUnit::getUnitNumber, String.CASE_INSENSITIVE_ORDER);

    /**
     * Las unidades que entran en los impuestos: las del usuario.
     * <p>
     * De aquí salen el 303, el 184 y el IRPF, así que filtrar en un solo sitio
     * los deja los tres dentro del ámbito. Ojo con lo que eso significa: quien no
     * ve todas las unidades de un propietario obtiene un informe <em>parcial</em>,
     * que no cuadra con lo que hay que presentar. La declaración de verdad la
     * saca quien lo ve todo (un gestor), y la pantalla avisa de ello.
     */
    private List<StorageUnit> allUnits() {
        return unitScope.filterByUnit(storageUnitRepository.findAll(), unit -> unit).stream()
                .sorted(UNIT_ORDER)
                .toList();
    }

    /** Units that can be rented (locales are containers, not rented units). */
    private List<StorageUnit> rentableUnits() {
        return allUnits().stream().filter(u -> !u.isContainer()).toList();
    }

    private static Set<Long> idsOf(List<StorageUnit> units) {
        Set<Long> ids = new HashSet<>();
        units.forEach(u -> ids.add(u.getId()));
        return ids;
    }

    private static Breakdown sum(List<Payment> payments, Predicate<Payment> filter, Function<Payment, BigDecimal> amount) {
        Breakdown acc = Breakdown.ZERO;
        for (Payment p : payments) {
            if (!filter.test(p)) continue;
            BigDecimal value = amount.apply(p);
            if (value == null) continue;
            boolean vat = p.getStorageUnit() == null || p.getStorageUnit().isVatApplicable();
            acc = acc.plus(VatUtils.breakdown(value, vat));
        }
        return acc;
    }

    private static boolean isPaid(Payment p) {
        return p.getStatus() == PaymentStatus.PAID;
    }

    private static boolean isOpen(Payment p) {
        return p.getStatus() == PaymentStatus.PENDING || p.getStatus() == PaymentStatus.OVERDUE;
    }

    /** PAID payments of the year of the given units, grouped by unit id. */
    private Map<Long, List<Payment>> paidPaymentsByUnit(int year, Set<Long> unitIds) {
        Map<Long, List<Payment>> byUnit = new HashMap<>();
        for (Payment p : paymentRepository.findByBillingPeriodYear(year)) {
            if (!isPaid(p) || p.getStorageUnit() == null || !unitIds.contains(p.getStorageUnit().getId())) continue;
            byUnit.computeIfAbsent(p.getStorageUnit().getId(), k -> new ArrayList<>()).add(p);
        }
        return byUnit;
    }

    private static Breakdown collected(List<Payment> payments) {
        return payments == null ? Breakdown.ZERO : sum(payments, TaxService::isPaid, Payment::getAmountPaid);
    }

    private Owner ownerOrThrow(Long ownerId) {
        return ownerRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Owner not found with id: " + ownerId));
    }

    /** The comunidad de bienes used when none is given: the first one by name, or null. */
    private Owner defaultEntity() {
        List<Owner> entities = ownerRepository.findByTypeOrderByFullNameAsc(OwnerType.COMUNIDAD_DE_BIENES);
        return entities.isEmpty() ? null : entities.get(0);
    }

    // ------------------------------------------------------------------
    // Modelo 303
    // ------------------------------------------------------------------

    /**
     * Quarterly VAT figures of the year for the VAT-bearing units; when {@code ownerId}
     * is given (normally the comunidad de bienes), only the units that owner holds a
     * share of - directly or through their local - are included.
     */
    public Modelo303DTO modelo303(int year, Long ownerId) {
        Owner owner = ownerId == null ? null : ownerOrThrow(ownerId);
        Map<Long, List<Ownership>> byUnit = OwnershipService.indexByUnit(ownershipRepository.findAll());
        List<StorageUnit> vatUnits = rentableUnits().stream()
                .filter(StorageUnit::isVatApplicable)
                .filter(u -> owner == null || OwnershipService.shareOf(owner.getId(), u, byUnit) != null)
                .toList();
        Set<Long> unitIds = idsOf(vatUnits);

        // Un cobro anulado marca un mes que no se cobra a nadie: no es un hecho
        // fiscal, así que no entra ni como ingreso ni como pendiente del trimestre.
        List<Payment> payments = paymentRepository.findByBillingPeriodYear(year).stream()
                .filter(p -> p.getStorageUnit() != null && unitIds.contains(p.getStorageUnit().getId()))
                .filter(p -> p.getStatus() != PaymentStatus.CANCELLED)
                .toList();

        // IVA soportado: los gastos del año de las unidades de esta comunidad que
        // declaran cuota de IVA. Se imputan por fecha del gasto, y sin prorratear
        // por la parte que se tenga de la unidad, igual que los ingresos.
        List<Expense> vatExpenses = expenseRepository
                .findByExpenseDateBetween(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)).stream()
                .filter(e -> e.deductibleVat().signum() > 0)
                .filter(e -> e.getStorageUnit() != null)
                .filter(e -> owner == null || OwnershipService.shareOf(owner.getId(), e.getStorageUnit(), byUnit) != null)
                .toList();

        List<Modelo303DTO.Quarter> quarters = new ArrayList<>();
        Breakdown collectedYear = Breakdown.ZERO;
        Breakdown expectedYear = Breakdown.ZERO;
        Breakdown pendingYear = Breakdown.ZERO;
        BigDecimal deductibleBaseYear = zero();
        BigDecimal deductibleVatYear = zero();
        long paidYear = 0;
        for (int q = 1; q <= 4; q++) {
            int startMonth = (q - 1) * 3 + 1;
            int endMonth = q * 3;
            List<Payment> quarterPayments = payments.stream()
                    .filter(p -> p.getBillingPeriodMonth() >= startMonth && p.getBillingPeriodMonth() <= endMonth)
                    .toList();
            Breakdown collected = sum(quarterPayments, TaxService::isPaid, Payment::getAmountPaid);
            Breakdown expected = sum(quarterPayments, p -> true, Payment::getAmountDue);
            Breakdown pending = sum(quarterPayments, TaxService::isOpen, Payment::getAmountDue);
            long paidCount = quarterPayments.stream().filter(TaxService::isPaid).count();
            long pendingCount = quarterPayments.stream().filter(p -> p.getStatus() == PaymentStatus.PENDING).count();
            long overdueCount = quarterPayments.stream().filter(p -> p.getStatus() == PaymentStatus.OVERDUE).count();
            List<Modelo303DTO.PaymentLine> lines = quarterPayments.stream()
                    .sorted(Comparator.comparing(Payment::getBillingPeriodMonth)
                            .thenComparing(p -> p.getStorageUnit().getUnitNumber().length())
                            .thenComparing(p -> p.getStorageUnit().getUnitNumber(), String.CASE_INSENSITIVE_ORDER))
                    .map(p -> {
                        BigDecimal amount = isPaid(p) ? p.getAmountPaid() : p.getAmountDue();
                        Breakdown b = VatUtils.breakdown(amount, p.getStorageUnit().isVatApplicable());
                        return Modelo303DTO.PaymentLine.builder()
                                .paymentId(p.getId())
                                .unitId(p.getStorageUnit().getId())
                                .unitNumber(p.getStorageUnit().getUnitNumber())
                                .unitName(p.getStorageUnit().getName())
                                .clientName(p.getClient() != null ? p.getClient().getFullName() : null)
                                .billingPeriodYear(p.getBillingPeriodYear())
                                .billingPeriodMonth(p.getBillingPeriodMonth())
                                .paymentDate(p.getPaymentDate())
                                .status(p.getStatus() != null ? p.getStatus().name() : null)
                                .total(b.total())
                                .base(b.base())
                                .vat(b.vat())
                                .build();
                    })
                    .toList();

            List<Expense> quarterExpenses = vatExpenses.stream()
                    .filter(e -> e.getExpenseDate().getMonthValue() >= startMonth
                            && e.getExpenseDate().getMonthValue() <= endMonth)
                    .sorted(Comparator.comparing(Expense::getExpenseDate).thenComparing(Expense::getId))
                    .toList();
            BigDecimal deductibleVat = zero();
            BigDecimal deductibleBase = zero();
            List<Modelo303DTO.ExpenseLine> expenseLines = new ArrayList<>();
            for (Expense e : quarterExpenses) {
                BigDecimal vat = money(e.deductibleVat());
                BigDecimal base = money(e.netAmount());
                deductibleVat = deductibleVat.add(vat);
                deductibleBase = deductibleBase.add(base);
                expenseLines.add(Modelo303DTO.ExpenseLine.builder()
                        .expenseId(e.getId())
                        .unitId(e.getStorageUnit().getId())
                        .unitNumber(e.getStorageUnit().getUnitNumber())
                        .unitName(e.getStorageUnit().getName())
                        .description(e.getDescription())
                        .category(e.getCategory() != null ? e.getCategory().name() : null)
                        .expenseDate(e.getExpenseDate())
                        .total(money(e.getAmount()))
                        .base(base)
                        .vat(vat)
                        .build());
            }
            deductibleBaseYear = deductibleBaseYear.add(deductibleBase);
            deductibleVatYear = deductibleVatYear.add(deductibleVat);

            quarters.add(Modelo303DTO.Quarter.builder()
                    .quarter(q)
                    .label(q + "T " + year)
                    .startMonth(startMonth)
                    .endMonth(endMonth)
                    .collectedBase(collected.base()).collectedVat(collected.vat()).collectedTotal(collected.total())
                    .expectedBase(expected.base()).expectedVat(expected.vat()).expectedTotal(expected.total())
                    .pendingBase(pending.base()).pendingVat(pending.vat()).pendingTotal(pending.total())
                    .paidCount(paidCount)
                    .pendingCount(pendingCount)
                    .overdueCount(overdueCount)
                    .deductibleBase(deductibleBase)
                    .deductibleVat(deductibleVat)
                    .resultVat(collected.vat().subtract(deductibleVat))
                    .payments(lines)
                    .expenses(expenseLines)
                    .build());
            collectedYear = collectedYear.plus(collected);
            expectedYear = expectedYear.plus(expected);
            pendingYear = pendingYear.plus(pending);
            paidYear += paidCount;
        }

        return Modelo303DTO.builder()
                .year(year)
                .ownerId(owner != null ? owner.getId() : null)
                .ownerName(owner != null ? owner.getFullName() : null)
                .unitNumbers(vatUnits.stream().map(StorageUnit::getUnitNumber).toList())
                .unitCount(vatUnits.size())
                .quarters(quarters)
                .collectedBase(collectedYear.base()).collectedVat(collectedYear.vat()).collectedTotal(collectedYear.total())
                .expectedBase(expectedYear.base()).expectedVat(expectedYear.vat()).expectedTotal(expectedYear.total())
                .pendingBase(pendingYear.base()).pendingVat(pendingYear.vat()).pendingTotal(pendingYear.total())
                .paidCount(paidYear)
                .deductibleBase(deductibleBaseYear)
                .deductibleVat(deductibleVatYear)
                .resultVat(collectedYear.vat().subtract(deductibleVatYear))
                .build();
    }

    // ------------------------------------------------------------------
    // Income of a comunidad de bienes (shared by the Modelo 184 and the IRPF)
    // ------------------------------------------------------------------

    /**
     * Lo que la entidad gana y gasta en un año. Lo que se atribuye a los miembros
     * es el rendimiento <em>neto</em> ({@link #net()}): una comunidad de bienes no
     * tributa, calcula el rendimiento como lo haría una persona —ingresos menos
     * gastos deducibles— y lo reparte; son sus miembros quienes lo declaran en su
     * IRPF. Atribuir el ingreso íntegro les haría declarar de más.
     */
    private record EntityIncome(Owner entity, Breakdown income, BigDecimal expenses,
                                Map<String, BigDecimal> expensesByCategory, List<Modelo184DTO.UnitShare> units) {
        BigDecimal net() {
            return income.base().subtract(expenses);
        }
    }

    /**
     * Gastos deducibles del año por unidad y categoría, ya netos del IVA soportado
     * que se deduce en el 303. Incluye las unidades contenedoras: los gastos
     * gordos de la comunidad (IBI, luz y seguro del local) van contra el local.
     */
    private Map<Long, Map<String, BigDecimal>> deductibleExpensesByUnit(int year) {
        Map<Long, Map<String, BigDecimal>> byUnit = new HashMap<>();
        for (Expense e : expenseRepository.findByExpenseDateBetween(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))) {
            if (!DEDUCTIBLE_CATEGORIES.contains(e.getCategory()) || e.getStorageUnit() == null) continue;
            addExpense(byUnit.computeIfAbsent(e.getStorageUnit().getId(), k -> new TreeMap<>()),
                    e.getCategory(), e.netAmount());
        }
        return byUnit;
    }

    /**
     * La parte de la entidad de los ingresos y los gastos deducibles del año de
     * cada unidad en la que participa. Recorre todas las unidades, no sólo las
     * alquilables: el local no se alquila pero soporta los gastos de las que sí.
     */
    private EntityIncome entityIncome(Owner entity, int year, Map<Long, List<Ownership>> byUnit,
                                      Map<Long, List<Payment>> paymentsByUnit) {
        Map<Long, Map<String, BigDecimal>> expensesByUnit = deductibleExpensesByUnit(year);
        Breakdown total = Breakdown.ZERO;
        BigDecimal expensesTotal = zero();
        Map<String, BigDecimal> expensesByCategory = new TreeMap<>();
        List<Modelo184DTO.UnitShare> units = new ArrayList<>();
        for (StorageUnit unit : allUnits()) {
            Effective effective = OwnershipService.resolve(unit, byUnit);
            BigDecimal percent = null;
            for (Ownership o : effective.shares()) {
                if (o.getOwner().getId().equals(entity.getId())) percent = o.getSharePercent();
            }
            if (percent == null) continue;
            Breakdown income = collected(paymentsByUnit.get(unit.getId()));
            Map<String, BigDecimal> unitExpenses = expensesByUnit.getOrDefault(unit.getId(), Map.of());
            BigDecimal unitExpenseTotal = sumValues(unitExpenses);
            // Una unidad sin ingresos ni gastos no dice nada en el modelo
            if (income.total().signum() == 0 && unitExpenseTotal.signum() == 0) continue;

            Breakdown entityPart = part(income, percent);
            Map<String, BigDecimal> entityExpenses = partOf(unitExpenses, percent);
            BigDecimal entityExpenseTotal = sumValues(entityExpenses);
            total = total.plus(entityPart);
            expensesTotal = expensesTotal.add(entityExpenseTotal);
            entityExpenses.forEach((category, amount) -> expensesByCategory.merge(category, amount, BigDecimal::add));

            units.add(Modelo184DTO.UnitShare.builder()
                    .unitId(unit.getId())
                    .unitNumber(unit.getUnitNumber())
                    .unitName(unit.getName())
                    .parentUnitNumber(parentNumber(unit))
                    .sharePercent(percent)
                    .inherited(effective.inherited(unit))
                    .unitIncomeBase(income.base())
                    .incomeBase(entityPart.base())
                    .incomeVat(entityPart.vat())
                    .incomeTotal(entityPart.total())
                    .unitExpenses(unitExpenseTotal)
                    .expenses(entityExpenseTotal)
                    .net(entityPart.base().subtract(entityExpenseTotal))
                    .build());
        }
        return new EntityIncome(entity, total, expensesTotal, expensesByCategory, units);
    }

    private List<OwnerMembership> membersOf(Owner entity) {
        return ownerMembershipRepository.findByEntityId(entity.getId()).stream()
                .sorted(Comparator.comparing(OwnerMembership::getSharePercent, Comparator.reverseOrder())
                        .thenComparing(m -> m.getMember().getFullName(), String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    // ------------------------------------------------------------------
    // Modelo 184
    // ------------------------------------------------------------------

    /**
     * Rent of the year of the units the comunidad de bienes holds, attributed to its
     * members by their membership percentages. {@code ownerId} null means the first
     * comunidad de bienes by name.
     */
    public Modelo184DTO modelo184(int year, Long ownerId) {
        Owner entity = ownerId != null ? ownerOrThrow(ownerId) : defaultEntity();
        if (entity == null) {
            return Modelo184DTO.builder()
                    .year(year)
                    .message("No hay ninguna comunidad de bienes registrada")
                    .incomeBase(zero()).incomeVat(zero()).incomeTotal(zero())
                    .attributedBase(zero()).unattributedBase(zero()).membersSharePercent(BigDecimal.ZERO)
                    .members(List.of())
                    .units(List.of())
                    .build();
        }
        if (!entity.isEntity()) {
            throw new BadRequestException("The Modelo 184 is filed by a comunidad de bienes; '" + entity.getFullName() + "' is a person");
        }
        Map<Long, List<Ownership>> byUnit = OwnershipService.indexByUnit(ownershipRepository.findAll());
        Map<Long, List<Payment>> paymentsByUnit = paidPaymentsByUnit(year, idsOf(rentableUnits()));
        EntityIncome ei = entityIncome(entity, year, byUnit, paymentsByUnit);

        List<Modelo184DTO.Member> members = new ArrayList<>();
        Breakdown attributed = Breakdown.ZERO;
        BigDecimal membersPercent = BigDecimal.ZERO;
        BigDecimal attributedNet = zero();
        for (OwnerMembership m : membersOf(entity)) {
            Breakdown memberPart = part(ei.income(), m.getSharePercent());
            BigDecimal memberExpenses = part(ei.expenses(), m.getSharePercent());
            BigDecimal memberNet = memberPart.base().subtract(memberExpenses);
            members.add(Modelo184DTO.Member.builder()
                    .ownerId(m.getMember().getId())
                    .ownerName(m.getMember().getFullName())
                    .sharePercent(m.getSharePercent())
                    .incomeBase(memberPart.base())
                    .incomeVat(memberPart.vat())
                    .incomeTotal(memberPart.total())
                    .expenses(memberExpenses)
                    .net(memberNet)
                    .build());
            attributed = attributed.plus(memberPart);
            attributedNet = attributedNet.add(memberNet);
            membersPercent = membersPercent.add(m.getSharePercent());
        }

        return Modelo184DTO.builder()
                .year(year)
                .entityId(entity.getId())
                .entityName(entity.getFullName())
                .unitCount(ei.units().size())
                .incomeBase(ei.income().base())
                .incomeVat(ei.income().vat())
                .incomeTotal(ei.income().total())
                .expenses(ei.expenses())
                .expensesByCategory(ei.expensesByCategory())
                .netBase(ei.net())
                .attributedBase(attributed.base())
                .unattributedBase(ei.income().base().subtract(attributed.base()))
                .attributedNet(attributedNet)
                .membersSharePercent(membersPercent)
                .members(members)
                .units(ei.units())
                .build();
    }

    // ------------------------------------------------------------------
    // IRPF
    // ------------------------------------------------------------------

    /** Accumulates the lines of one block (rental or attribution) of a person. */
    private static final class SectionAcc {
        final List<IrpfReportDTO.Line> lines = new ArrayList<>();
        Breakdown income = Breakdown.ZERO;
        final Map<String, BigDecimal> expensesByCategory = new TreeMap<>();
        BigDecimal expenses = zero();

        void add(IrpfReportDTO.Line line) {
            lines.add(line);
            income = income.plus(new Breakdown(line.getIncomeTotal(), line.getIncomeBase(), line.getIncomeVat()));
            line.getExpensesByCategory().forEach((cat, amount) -> expensesByCategory.merge(cat, amount, BigDecimal::add));
            expenses = expenses.add(line.getExpenses());
        }

        IrpfReportDTO.Section build() {
            return IrpfReportDTO.Section.builder()
                    .lines(lines)
                    .incomeBase(income.base())
                    .incomeVat(income.vat())
                    .incomeTotal(income.total())
                    .expensesByCategory(expensesByCategory)
                    .expenses(expenses)
                    .net(income.base().subtract(expenses))
                    .build();
        }
    }

    private static final class OwnerYield {
        final Owner owner;
        final SectionAcc rental = new SectionAcc();
        final SectionAcc attribution = new SectionAcc();

        OwnerYield(Owner owner) {
            this.owner = owner;
        }
    }

    private static void addExpense(Map<String, BigDecimal> map, ExpenseCategory category, BigDecimal amount) {
        map.merge(category.name(), money(amount), BigDecimal::add);
    }

    private static BigDecimal sumValues(Map<String, BigDecimal> map) {
        return map.values().stream().reduce(zero(), BigDecimal::add);
    }

    private static Map<String, BigDecimal> partOf(Map<String, BigDecimal> map, BigDecimal percent) {
        Map<String, BigDecimal> result = new TreeMap<>();
        map.forEach((cat, amount) -> result.put(cat, part(amount, percent)));
        return result;
    }

    private static List<IrpfReportDTO.Rental> rentalsOf(List<Payment> payments) {
        if (payments == null) return List.of();
        Map<Long, List<Payment>> byRental = new LinkedHashMap<>();
        for (Payment p : payments) {
            if (p.getRentalAgreement() == null) continue;
            byRental.computeIfAbsent(p.getRentalAgreement().getId(), k -> new ArrayList<>()).add(p);
        }
        List<IrpfReportDTO.Rental> rentals = new ArrayList<>();
        for (List<Payment> group : byRental.values()) {
            RentalAgreement r = group.get(0).getRentalAgreement();
            Breakdown income = collected(group);
            rentals.add(IrpfReportDTO.Rental.builder()
                    .rentalId(r.getId())
                    .agreementNumber(r.getAgreementNumber())
                    .clientName(r.getClient() != null ? r.getClient().getFullName() : null)
                    .clientDocumentId(r.getClient() != null ? r.getClient().getDocumentId() : null)
                    .coClientName(r.getCoClient() != null ? r.getCoClient().getFullName() : null)
                    .coClientDocumentId(r.getCoClient() != null ? r.getCoClient().getDocumentId() : null)
                    .startDate(r.getStartDate())
                    .endDate(r.getEndDate())
                    .status(r.getStatus() != null ? r.getStatus().name() : null)
                    .paidMonths(group.size())
                    .incomeTotal(income.total())
                    .incomeBase(income.base())
                    .incomeVat(income.vat())
                    .build());
        }
        rentals.sort(Comparator.comparing(IrpfReportDTO.Rental::getStartDate, Comparator.nullsLast(Comparator.<LocalDate>naturalOrder())));
        return rentals;
    }

    /**
     * For every person: their part of the income and deductible expenses of the units
     * they hold directly, plus the income each comunidad de bienes attributes to them.
     */
    public IrpfReportDTO irpf(int year) {
        List<Owner> owners = ownerRepository.findAllByOrderByFullNameAsc();
        List<Owner> entities = owners.stream().filter(Owner::isEntity).toList();
        List<StorageUnit> units = allUnits();
        Map<Long, List<Ownership>> byUnit = OwnershipService.indexByUnit(ownershipRepository.findAll());
        Map<Long, List<Payment>> paymentsByUnit = paidPaymentsByUnit(year, idsOf(units));

        // --- Deductible expenses of the year, on their unit; general ones cannot be attributed
        Map<Long, Map<String, BigDecimal>> unitExpenses = new HashMap<>();
        Map<String, BigDecimal> excluded = new TreeMap<>();
        BigDecimal unassigned = zero();
        for (Expense e : expenseRepository.findByExpenseDateBetween(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))) {
            if (!DEDUCTIBLE_CATEGORIES.contains(e.getCategory())) {
                addExpense(excluded, e.getCategory(), e.getAmount());
            } else if (e.getStorageUnit() == null) {
                unassigned = unassigned.add(money(e.netAmount()));
            } else {
                // Sin el IVA soportado: esa cuota se deduce en el 303, y volver a
                // restarla aquí sería descontar dos veces lo mismo.
                addExpense(unitExpenses.computeIfAbsent(e.getStorageUnit().getId(), k -> new TreeMap<>()), e.getCategory(), e.netAmount());
            }
        }

        // --- Directly-held units: each person's part of income and expenses
        Map<Long, OwnerYield> byOwner = new LinkedHashMap<>();
        for (Owner person : owners) {
            if (!person.isEntity()) byOwner.put(person.getId(), new OwnerYield(person));
        }
        Breakdown totalRentalIncome = Breakdown.ZERO;
        BigDecimal totalRentalExpenses = zero();
        BigDecimal entityExpenses = zero();
        Breakdown unattributedIncome = Breakdown.ZERO;
        BigDecimal unattributedExpenses = unassigned;
        List<String> unitsWithoutOwners = new ArrayList<>();

        for (StorageUnit unit : units) {
            List<Payment> unitPayments = paymentsByUnit.get(unit.getId());
            Breakdown income = collected(unitPayments);
            Map<String, BigDecimal> expensesOfUnit = unitExpenses.getOrDefault(unit.getId(), Map.of());
            BigDecimal unitExpenseTotal = sumValues(expensesOfUnit);
            if (income.total().signum() == 0 && unitExpenseTotal.signum() == 0) continue;

            Effective effective = OwnershipService.resolve(unit, byUnit);
            if (effective.shares().isEmpty()) {
                unitsWithoutOwners.add(unit.getUnitNumber());
                unattributedIncome = unattributedIncome.plus(income);
                unattributedExpenses = unattributedExpenses.add(unitExpenseTotal);
                continue;
            }
            boolean inherited = effective.inherited(unit);
            List<IrpfReportDTO.Rental> rentals = rentalsOf(unitPayments);
            for (Ownership o : effective.shares()) {
                BigDecimal percent = o.getSharePercent();
                if (o.getOwner().isEntity()) {
                    entityExpenses = entityExpenses.add(part(unitExpenseTotal, percent));
                    continue;
                }
                Breakdown ownerIncome = part(income, percent);
                Map<String, BigDecimal> ownerExpenses = partOf(expensesOfUnit, percent);
                BigDecimal ownerExpenseTotal = sumValues(ownerExpenses);
                totalRentalIncome = totalRentalIncome.plus(ownerIncome);
                totalRentalExpenses = totalRentalExpenses.add(ownerExpenseTotal);
                IrpfReportDTO.Line line = IrpfReportDTO.Line.builder()
                        .scope("UNIT")
                        .unitId(unit.getId())
                        .unitNumber(unit.getUnitNumber())
                        .unitName(unit.getName())
                        .kind(unit.getKind().name())
                        .vatApplicable(unit.isVatApplicable())
                        .parentUnitId(unit.getParent() != null ? unit.getParent().getId() : null)
                        .parentUnitNumber(parentNumber(unit))
                        .parentUnitName(unit.getParent() != null ? unit.getParent().getName() : null)
                        .cadastralReference(unit.getCadastralReference())
                        .sharePercent(percent)
                        .inherited(inherited)
                        .unitIncomeBase(income.base())
                        .unitIncomeTotal(income.total())
                        .incomeBase(ownerIncome.base())
                        .incomeVat(ownerIncome.vat())
                        .incomeTotal(ownerIncome.total())
                        .rentals(rentals)
                        .expensesByCategory(ownerExpenses)
                        .unitExpenses(unitExpenseTotal)
                        .expenses(ownerExpenseTotal)
                        .net(ownerIncome.base().subtract(ownerExpenseTotal))
                        .build();
                byOwner.computeIfAbsent(o.getOwner().getId(), k -> new OwnerYield(o.getOwner())).rental.add(line);
            }
        }

        // --- Atribución de rentas: each entity's income by membership percentage
        Breakdown totalAttribution = Breakdown.ZERO;
        BigDecimal totalAttributionExpenses = zero();
        for (Owner entity : entities) {
            EntityIncome ei = entityIncome(entity, year, byUnit, paymentsByUnit);
            // Nothing to attribute (e.g. years before the trasteros existed): no lines, so members
            // without direct rentals do not show up with zeros
            if (ei.income().total().signum() == 0) continue;
            for (OwnerMembership m : membersOf(entity)) {
                Breakdown memberPart = part(ei.income(), m.getSharePercent());
                // La comunidad no tributa: atribuye su rendimiento neto, así que
                // cada miembro se lleva también su parte de los gastos.
                Map<String, BigDecimal> memberExpensesByCategory = partOf(ei.expensesByCategory(), m.getSharePercent());
                BigDecimal memberExpenses = sumValues(memberExpensesByCategory);
                totalAttribution = totalAttribution.plus(memberPart);
                totalAttributionExpenses = totalAttributionExpenses.add(memberExpenses);
                IrpfReportDTO.Line line = IrpfReportDTO.Line.builder()
                        .scope("ENTITY")
                        .entityId(entity.getId())
                        .entityName(entity.getFullName())
                        .sharePercent(m.getSharePercent())
                        .inherited(false)
                        .unitIncomeBase(ei.income().base())
                        .unitIncomeTotal(ei.income().total())
                        .incomeBase(memberPart.base())
                        .incomeVat(memberPart.vat())
                        .incomeTotal(memberPart.total())
                        .rentals(List.of())
                        .expensesByCategory(memberExpensesByCategory)
                        .unitExpenses(ei.expenses())
                        .expenses(memberExpenses)
                        .net(memberPart.base().subtract(memberExpenses))
                        .build();
                byOwner.computeIfAbsent(m.getMember().getId(), k -> new OwnerYield(m.getMember())).attribution.add(line);
            }
        }

        List<IrpfReportDTO.OwnerReport> reports = new ArrayList<>();
        for (OwnerYield acc : byOwner.values()) {
            if (acc.rental.lines.isEmpty() && acc.attribution.lines.isEmpty()) continue;
            IrpfReportDTO.Section rental = acc.rental.build();
            IrpfReportDTO.Section attribution = acc.attribution.build();
            reports.add(IrpfReportDTO.OwnerReport.builder()
                    .ownerId(acc.owner.getId())
                    .ownerName(acc.owner.getFullName())
                    .rental(rental)
                    .attribution(attribution)
                    .totalNet(rental.getNet().add(attribution.getNet()))
                    .build());
        }
        reports.sort(Comparator.comparing(IrpfReportDTO.OwnerReport::getOwnerName, String.CASE_INSENSITIVE_ORDER));

        return IrpfReportDTO.builder()
                .year(year)
                .entityNames(entities.stream().map(Owner::getFullName).toList())
                .deductibleCategories(DEDUCTIBLE_CATEGORIES.stream().map(Enum::name).toList())
                .owners(reports)
                .totalRentalIncomeBase(totalRentalIncome.base())
                .totalRentalExpenses(totalRentalExpenses)
                .totalRentalNet(totalRentalIncome.base().subtract(totalRentalExpenses))
                .totalAttributionIncomeBase(totalAttribution.base())
                .totalAttributionExpenses(totalAttributionExpenses)
                .totalAttributionNet(totalAttribution.base().subtract(totalAttributionExpenses))
                .unattributedIncomeBase(unattributedIncome.base())
                .unattributedExpenses(unattributedExpenses)
                .unitsWithoutOwners(unitsWithoutOwners)
                .entityExpenses(entityExpenses)
                .excludedExpensesByCategory(excluded)
                .excludedExpenses(sumValues(excluded))
                .build();
    }
}
