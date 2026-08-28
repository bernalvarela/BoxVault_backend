package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.config.VatUtils;
import com.storagemanager.storage_management.config.VatUtils.Breakdown;
import com.storagemanager.storage_management.dto.AnnualRevenueDTO;
import com.storagemanager.storage_management.dto.DashboardStatsDTO;
import com.storagemanager.storage_management.dto.ExpenseCategorySummaryDTO;
import com.storagemanager.storage_management.dto.HistoryRangeDTO;
import com.storagemanager.storage_management.dto.MonthlyRevenueDTO;
import com.storagemanager.storage_management.dto.QuarterlyRevenueDTO;
import com.storagemanager.storage_management.dto.UnitOccupancyDTO;
import com.storagemanager.storage_management.dto.UnitRevenueDTO;
import com.storagemanager.storage_management.model.Payment;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.model.enums.PaymentStatus;
import com.storagemanager.storage_management.model.enums.RentalStatus;
import com.storagemanager.storage_management.model.enums.UnitKind;
import com.storagemanager.storage_management.model.enums.UnitStatus;
import com.storagemanager.storage_management.repository.ClientRepository;
import com.storagemanager.storage_management.repository.PaymentRepository;
import com.storagemanager.storage_management.repository.RentalAgreementRepository;
import com.storagemanager.storage_management.repository.StorageUnitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Dashboard and trend statistics.
 * <p>
 * Every public method takes an optional collection of storage-group ids. A null or
 * empty collection means "all groups" (no filtering); otherwise only the units of
 * those groups - and their rentals, payments and expenses - are taken into account.
 * Group filtering is applied in memory on top of the period queries, which is more
 * than fast enough for the size of this data set and keeps a single code path.
 * <p>
 * VAT breakdowns are accumulated payment by payment (or unit by unit), because
 * storage units carry 21% VAT while apartments are exempt: the base of a mixed
 * total is the sum of each item's base, not the total divided by 1.21.
 */
@Service
@RequiredArgsConstructor
public class StatisticsService {

    private static final DateTimeFormatter MONTH_LABEL_FORMATTER =
            DateTimeFormatter.ofPattern("MMM yyyy", new Locale("es", "ES"));

    private final StorageUnitRepository storageUnitRepository;
    private final ClientRepository clientRepository;
    private final RentalAgreementRepository rentalAgreementRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;
    private final ExpenseService expenseService;

    // ------------------------------------------------------------------
    // Group filter helpers
    // ------------------------------------------------------------------

    /** Normalises the API filter: null, empty, or only-null ids -> null ("every group"). */
    static Set<Long> normalizeGroupIds(Collection<Long> groupIds) {
        if (groupIds == null) return null;
        Set<Long> set = new LinkedHashSet<>();
        for (Long id : groupIds) {
            if (id != null) set.add(id);
        }
        return set.isEmpty() ? null : set;
    }

    static boolean unitInGroups(StorageUnit unit, Set<Long> groupIds) {
        if (groupIds == null) return true;
        return unit != null && unit.getStorageGroup() != null && groupIds.contains(unit.getStorageGroup().getId());
    }

    private List<StorageUnit> unitsIn(Set<Long> groupIds) {
        return groupIds == null ? storageUnitRepository.findAll() : storageUnitRepository.findByStorageGroupIdIn(groupIds);
    }

    private static List<Payment> paymentsIn(List<Payment> payments, Set<Long> groupIds) {
        if (groupIds == null) return payments;
        return payments.stream().filter(p -> unitInGroups(p.getStorageUnit(), groupIds)).toList();
    }

    private static List<RentalAgreement> rentalsIn(List<RentalAgreement> rentals, Set<Long> groupIds) {
        if (groupIds == null) return rentals;
        return rentals.stream().filter(r -> unitInGroups(r.getStorageUnit(), groupIds)).toList();
    }

    // ------------------------------------------------------------------
    // VAT-aware sums
    // ------------------------------------------------------------------

    private static boolean vatOf(StorageUnit unit) {
        return unit == null || unit.isVatApplicable();
    }

    /** Sums an amount of the matching payments, splitting base/VAT according to each payment's unit. */
    private static Breakdown sum(List<Payment> payments, Predicate<Payment> filter, Function<Payment, BigDecimal> amount) {
        Breakdown acc = Breakdown.ZERO;
        for (Payment p : payments) {
            if (!filter.test(p)) continue;
            BigDecimal value = amount.apply(p);
            if (value == null) continue;
            acc = acc.plus(VatUtils.breakdown(value, vatOf(p.getStorageUnit())));
        }
        return acc;
    }

    private static long count(List<Payment> payments, PaymentStatus status) {
        return payments.stream().filter(p -> p.getStatus() == status).count();
    }

    private static boolean isOpen(Payment p) {
        return p.getStatus() == PaymentStatus.PENDING || p.getStatus() == PaymentStatus.OVERDUE;
    }

    /**
     * Revenue figures of a set of payments belonging to one period:
     * expected = every amount due, collected = amount paid of PAID payments,
     * pending = amount due of PENDING/OVERDUE payments.
     */
    private record PeriodTotals(Breakdown expected, Breakdown collected, Breakdown pending,
                                long paidCount, long pendingCount, long overdueCount) {
        static PeriodTotals of(List<Payment> payments) {
            return new PeriodTotals(
                    sum(payments, p -> true, Payment::getAmountDue),
                    sum(payments, p -> p.getStatus() == PaymentStatus.PAID, Payment::getAmountPaid),
                    sum(payments, StatisticsService::isOpen, Payment::getAmountDue),
                    count(payments, PaymentStatus.PAID),
                    count(payments, PaymentStatus.PENDING),
                    count(payments, PaymentStatus.OVERDUE));
        }
    }

    // ------------------------------------------------------------------
    // Dashboard
    // ------------------------------------------------------------------

    public DashboardStatsDTO getDashboardStats() {
        return getDashboardStats(null);
    }

    public DashboardStatsDTO getDashboardStats(Collection<Long> groupIdsParam) {
        paymentService.checkAndUpdateOverduePayments();
        Set<Long> groupIds = normalizeGroupIds(groupIdsParam);

        YearMonth current = YearMonth.now();
        List<Payment> currentMonthPayments = paymentsIn(
                paymentRepository.findByBillingPeriodYearAndBillingPeriodMonth(current.getYear(), current.getMonthValue()),
                groupIds);
        PeriodTotals month = PeriodTotals.of(currentMonthPayments);

        Breakdown expected = month.expected();
        if (expected.total().compareTo(BigDecimal.ZERO) == 0) {
            // Si aún no se generaron facturas en el mes, calcular en base a contratos activos
            expected = Breakdown.ZERO;
            for (RentalAgreement r : rentalsIn(rentalAgreementRepository.findAllActiveRentals(), groupIds)) {
                if (r.getMonthlyRent() != null) {
                    expected = expected.plus(VatUtils.breakdown(r.getMonthlyRent(), vatOf(r.getStorageUnit())));
                }
            }
        }

        // Gastos del mes en curso y desglose histórico por categoría
        BigDecimal currentMonthExpenses = expenseService.sumExpensesForMonth(current, groupIds);
        long currentMonthExpenseCount = expenseService.countExpensesForMonth(current, groupIds);
        List<ExpenseCategorySummaryDTO> expensesByCategory = expenseService.summarizeByCategory(null, null, groupIds);

        // 6 Meses de Histórico
        List<MonthlyRevenueDTO> recentMonthlyRevenue = monthlyTrends(current.minusMonths(5), current, groupIds);

        return buildStats(groupIds, expected, month.collected(), month.pending(),
                currentMonthExpenses, currentMonthExpenseCount, recentMonthlyRevenue, expensesByCategory);
    }

    /**
     * Statistics for a custom date range (payments by due date, expenses by date).
     * @param startDate start date (inclusive)
     * @param endDate end date (inclusive)
     */
    public DashboardStatsDTO getStatisticsByDateRange(LocalDate startDate, LocalDate endDate) {
        return getStatisticsByDateRange(startDate, endDate, null);
    }

    public DashboardStatsDTO getStatisticsByDateRange(LocalDate startDate, LocalDate endDate, Collection<Long> groupIdsParam) {
        if (startDate == null || endDate == null || startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("Invalid date range");
        }
        Set<Long> groupIds = normalizeGroupIds(groupIdsParam);

        List<Payment> rangePayments = paymentsIn(paymentRepository.findByDueDateBetween(startDate, endDate), groupIds);
        PeriodTotals range = PeriodTotals.of(rangePayments);

        // Gastos dentro del rango
        BigDecimal rangeExpenses = expenseService.sumExpensesBetween(startDate, endDate, groupIds);
        long rangeExpenseCount = expenseService.countExpensesBetween(startDate, endDate, groupIds);
        List<ExpenseCategorySummaryDTO> expensesByCategory = expenseService.summarizeByCategory(startDate, endDate, groupIds);

        // Month-by-month breakdown covering every month touched by the range
        List<MonthlyRevenueDTO> recentMonthlyRevenue =
                monthlyTrends(YearMonth.from(startDate), YearMonth.from(endDate), groupIds);

        return buildStats(groupIds, range.expected(), range.collected(), range.pending(),
                rangeExpenses, rangeExpenseCount, recentMonthlyRevenue, expensesByCategory);
    }

    /**
     * First and last date with recorded activity for the groups - a payment due date
     * (the same date the range statistics filter on) or an expense - so the UI can
     * offer a "whole history" range. Both dates are null when nothing is recorded.
     */
    public HistoryRangeDTO getHistoryRange(Collection<Long> groupIdsParam) {
        Set<Long> groupIds = normalizeGroupIds(groupIdsParam);
        LocalDate first = null;
        LocalDate last = null;
        for (Payment p : paymentsIn(paymentRepository.findAll(), groupIds)) {
            LocalDate due = p.getDueDate();
            if (due == null) continue;
            if (first == null || due.isBefore(first)) first = due;
            if (last == null || due.isAfter(last)) last = due;
        }
        LocalDate firstExpense = expenseService.firstExpenseDate(groupIds);
        LocalDate lastExpense = expenseService.lastExpenseDate(groupIds);
        if (firstExpense != null && (first == null || firstExpense.isBefore(first))) first = firstExpense;
        if (lastExpense != null && (last == null || lastExpense.isAfter(last))) last = lastExpense;
        return HistoryRangeDTO.builder().firstDate(first).lastDate(last).build();
    }

    /**
     * Assembles the dashboard DTO: the "current period" figures are supplied by the
     * caller (current month or custom range); the structural and all-time figures
     * (units, clients, overdue, historical totals) are computed here for the groups.
     */
    private DashboardStatsDTO buildStats(Set<Long> groupIds,
                                         Breakdown periodExpected,
                                         Breakdown periodCollected,
                                         Breakdown periodPending,
                                         BigDecimal periodExpenses,
                                         long periodExpenseCount,
                                         List<MonthlyRevenueDTO> recentMonthlyRevenue,
                                         List<ExpenseCategorySummaryDTO> expensesByCategory) {
        List<StorageUnit> units = unitsIn(groupIds);
        long totalUnits = units.size();
        long storageUnitCount = units.stream().filter(u -> u.getKind() == UnitKind.STORAGE_UNIT).count();
        long apartmentCount = units.stream().filter(u -> u.getKind() == UnitKind.APARTMENT).count();
        long occupiedUnits = units.stream().filter(u -> u.getStatus() == UnitStatus.OCCUPIED).count();
        long availableUnits = units.stream().filter(u -> u.getStatus() == UnitStatus.AVAILABLE).count();
        long maintenanceUnits = units.stream().filter(u -> u.getStatus() == UnitStatus.MAINTENANCE).count();
        long reservedUnits = units.stream().filter(u -> u.getStatus() == UnitStatus.RESERVED).count();

        double occupancyRate = totalUnits > 0 ? ((double) occupiedUnits / totalUnits) * 100.0 : 0.0;
        occupancyRate = Math.round(occupancyRate * 10.0) / 10.0;

        List<RentalAgreement> activeRentals = rentalsIn(rentalAgreementRepository.findByStatus(RentalStatus.ACTIVE), groupIds);
        long activeClients = activeRentals.stream().map(r -> r.getClient().getId()).distinct().count();
        // Sin filtro: todos los clientes; con filtro: clientes que han alquilado (alguna vez) en esos grupos
        long totalClients = groupIds == null
                ? clientRepository.count()
                : rentalsIn(rentalAgreementRepository.findAll(), groupIds).stream()
                        .map(r -> r.getClient().getId()).distinct().count();

        Breakdown potential = Breakdown.ZERO;
        for (StorageUnit unit : units) {
            if (unit.getBaseMonthlyRate() != null) {
                potential = potential.plus(VatUtils.breakdown(unit.getBaseMonthlyRate(), unit.isVatApplicable()));
            }
        }

        List<Payment> overduePayments = paymentsIn(paymentRepository.findByStatus(PaymentStatus.OVERDUE), groupIds);
        Breakdown overdue = sum(overduePayments, p -> true, Payment::getAmountDue);

        Breakdown allTime = sum(paymentsIn(paymentRepository.findByStatus(PaymentStatus.PAID), groupIds),
                p -> true, Payment::getAmountPaid);
        BigDecimal totalExpensesAllTime = expenseService.sumTotalExpenses(groupIds);

        // Desglose de Trasteros
        List<UnitOccupancyDTO> unitsSummary = getUnitSummaryList(units, activeRentals);

        return DashboardStatsDTO.builder()
                .totalUnits(totalUnits)
                .storageUnitCount(storageUnitCount)
                .apartmentCount(apartmentCount)
                .occupiedUnits(occupiedUnits)
                .availableUnits(availableUnits)
                .maintenanceUnits(maintenanceUnits)
                .reservedUnits(reservedUnits)
                .occupancyRate(occupancyRate)
                .totalClients(totalClients)
                .activeClients(activeClients)
                .activeRentals(activeRentals.size())
                // Potencial mensual con IVA y desglose
                .monthlyPotentialRevenue(potential.total())
                .monthlyPotentialRevenueWithoutVat(potential.base())
                .monthlyPotentialVatAmount(potential.vat())
                // Esperado en el periodo con IVA y desglose
                .currentMonthExpectedRevenue(periodExpected.total())
                .currentMonthExpectedRevenueWithoutVat(periodExpected.base())
                .currentMonthExpectedVatAmount(periodExpected.vat())
                // Cobrado en el periodo con IVA y desglose
                .currentMonthCollectedRevenue(periodCollected.total())
                .currentMonthCollectedRevenueWithoutVat(periodCollected.base())
                .currentMonthCollectedVatAmount(periodCollected.vat())
                // Pendiente en el periodo con IVA y desglose
                .currentMonthPendingRevenue(periodPending.total())
                .currentMonthPendingRevenueWithoutVat(periodPending.base())
                .currentMonthPendingVatAmount(periodPending.vat())
                // Vencidos con IVA y desglose
                .totalOverdueAmount(overdue.total())
                .totalOverdueWithoutVat(overdue.base())
                .totalOverdueVatAmount(overdue.vat())
                .overduePaymentCount(overduePayments.size())
                // Histórico total con IVA y desglose
                .totalRevenueAllTime(allTime.total())
                .totalRevenueAllTimeWithoutVat(allTime.base())
                .totalRevenueAllTimeVatAmount(allTime.vat())
                // Gastos y resultado neto
                .currentMonthExpenses(periodExpenses)
                .currentMonthExpenseCount(periodExpenseCount)
                .currentMonthNetResult(periodCollected.total().subtract(periodExpenses))
                .totalExpensesAllTime(totalExpensesAllTime)
                .netResultAllTime(allTime.total().subtract(totalExpensesAllTime))
                .recentMonthlyRevenue(recentMonthlyRevenue)
                .unitsSummary(unitsSummary)
                .expensesByCategory(expensesByCategory)
                .build();
    }

    // ------------------------------------------------------------------
    // Trends
    // ------------------------------------------------------------------

    public List<MonthlyRevenueDTO> getRecentMonthlyTrends(int numberOfMonths) {
        return getRecentMonthlyTrends(numberOfMonths, null);
    }

    public List<MonthlyRevenueDTO> getRecentMonthlyTrends(int numberOfMonths, Collection<Long> groupIds) {
        // Valores menores que 1 se interpretan como "solo el periodo actual"
        numberOfMonths = Math.max(1, numberOfMonths);
        YearMonth current = YearMonth.now();
        return monthlyTrends(current.minusMonths(numberOfMonths - 1L), current, normalizeGroupIds(groupIds));
    }

    /**
     * Month-by-month revenue for every month from {@code start} to {@code end} (both inclusive),
     * in chronological order.
     */
    public List<MonthlyRevenueDTO> getMonthlyTrendsBetween(YearMonth start, YearMonth end) {
        return monthlyTrends(start, end, null);
    }

    private List<MonthlyRevenueDTO> monthlyTrends(YearMonth start, YearMonth end, Set<Long> groupIds) {
        List<MonthlyRevenueDTO> list = new ArrayList<>();
        for (YearMonth ym = start; !ym.isAfter(end); ym = ym.plusMonths(1)) {
            list.add(buildMonthlyRevenue(ym, groupIds));
        }
        return list;
    }

    private MonthlyRevenueDTO buildMonthlyRevenue(YearMonth yearMonth, Set<Long> groupIds) {
        int year = yearMonth.getYear();
        int month = yearMonth.getMonthValue();
        String label = yearMonth.format(MONTH_LABEL_FORMATTER);

        List<Payment> payments = paymentsIn(
                paymentRepository.findByBillingPeriodYearAndBillingPeriodMonth(year, month), groupIds);
        PeriodTotals t = PeriodTotals.of(payments);

        BigDecimal expenses = expenseService.sumExpensesForMonth(yearMonth, groupIds);
        long expenseCount = expenseService.countExpensesForMonth(yearMonth, groupIds);

        return MonthlyRevenueDTO.builder()
                .monthLabel(label)
                .year(year)
                .month(month)
                .expectedRevenue(t.expected().total())
                .expectedRevenueWithoutVat(t.expected().base())
                .expectedVatAmount(t.expected().vat())
                .collectedRevenue(t.collected().total())
                .collectedRevenueWithoutVat(t.collected().base())
                .collectedVatAmount(t.collected().vat())
                .pendingRevenue(t.pending().total())
                .pendingRevenueWithoutVat(t.pending().base())
                .pendingVatAmount(t.pending().vat())
                .paidCount(t.paidCount())
                .pendingCount(t.pendingCount())
                .overdueCount(t.overdueCount())
                .expenses(expenses)
                .expenseCount(expenseCount)
                .netResult(t.collected().total().subtract(expenses))
                .build();
    }

    /**
     * Quarterly revenue trends for the specified number of quarters, oldest first.
     * @param numberOfQuarters number of quarters to go back from current quarter
     */
    public List<QuarterlyRevenueDTO> getQuarterlyTrends(int numberOfQuarters) {
        return getQuarterlyTrends(numberOfQuarters, null);
    }

    public List<QuarterlyRevenueDTO> getQuarterlyTrends(int numberOfQuarters, Collection<Long> groupIdsParam) {
        // Valores menores que 1 se interpretan como "solo el periodo actual"
        numberOfQuarters = Math.max(1, numberOfQuarters);
        Set<Long> groupIds = normalizeGroupIds(groupIdsParam);
        LocalDate current = LocalDate.now();
        List<QuarterlyRevenueDTO> list = new ArrayList<>();

        for (int i = numberOfQuarters - 1; i >= 0; i--) {
            // Calculate target date by going back i quarters (3 months each)
            LocalDate targetDate = current.minusMonths(3L * i);
            int year = targetDate.getYear();
            int quarter = (targetDate.getMonthValue() - 1) / 3 + 1; // Q1: Jan-Mar, Q2: Apr-Jun, etc.
            int startMonth = (quarter - 1) * 3 + 1;
            int endMonth = quarter * 3;

            List<Payment> quarterPayments = paymentsIn(
                    paymentRepository.findByBillingPeriodYearAndBillingPeriodMonthBetween(year, startMonth, endMonth),
                    groupIds);
            PeriodTotals t = PeriodTotals.of(quarterPayments);

            LocalDate quarterStart = LocalDate.of(year, startMonth, 1);
            LocalDate quarterEnd = YearMonth.of(year, endMonth).atEndOfMonth();
            BigDecimal expenses = expenseService.sumExpensesBetween(quarterStart, quarterEnd, groupIds);
            long expenseCount = expenseService.countExpensesBetween(quarterStart, quarterEnd, groupIds);

            list.add(QuarterlyRevenueDTO.builder()
                    .quarterLabel("Q" + quarter + " " + year)
                    .year(year)
                    .quarter(quarter)
                    .expectedRevenue(t.expected().total())
                    .expectedRevenueWithoutVat(t.expected().base())
                    .expectedVatAmount(t.expected().vat())
                    .collectedRevenue(t.collected().total())
                    .collectedRevenueWithoutVat(t.collected().base())
                    .collectedVatAmount(t.collected().vat())
                    .pendingRevenue(t.pending().total())
                    .pendingRevenueWithoutVat(t.pending().base())
                    .pendingVatAmount(t.pending().vat())
                    .paidCount(t.paidCount())
                    .pendingCount(t.pendingCount())
                    .overdueCount(t.overdueCount())
                    .expenses(expenses)
                    .expenseCount(expenseCount)
                    .netResult(t.collected().total().subtract(expenses))
                    .build());
        }

        return list;
    }

    /**
     * Annual revenue trends for the specified number of years, oldest first.
     * @param numberOfYears number of years to go back from current year
     */
    public List<AnnualRevenueDTO> getAnnualTrends(int numberOfYears) {
        return getAnnualTrends(numberOfYears, null);
    }

    public List<AnnualRevenueDTO> getAnnualTrends(int numberOfYears, Collection<Long> groupIdsParam) {
        // Valores menores que 1 se interpretan como "solo el periodo actual"
        numberOfYears = Math.max(1, numberOfYears);
        Set<Long> groupIds = normalizeGroupIds(groupIdsParam);
        LocalDate current = LocalDate.now();
        List<AnnualRevenueDTO> list = new ArrayList<>();

        for (int i = numberOfYears - 1; i >= 0; i--) {
            int year = current.getYear() - i;

            List<Payment> yearPayments = paymentsIn(paymentRepository.findByBillingPeriodYear(year), groupIds);
            PeriodTotals t = PeriodTotals.of(yearPayments);

            LocalDate yearStart = LocalDate.of(year, 1, 1);
            LocalDate yearEnd = LocalDate.of(year, 12, 31);
            BigDecimal expenses = expenseService.sumExpensesBetween(yearStart, yearEnd, groupIds);
            long expenseCount = expenseService.countExpensesBetween(yearStart, yearEnd, groupIds);

            list.add(AnnualRevenueDTO.builder()
                    .yearLabel(String.valueOf(year))
                    .year(year)
                    .expectedRevenue(t.expected().total())
                    .expectedRevenueWithoutVat(t.expected().base())
                    .expectedVatAmount(t.expected().vat())
                    .collectedRevenue(t.collected().total())
                    .collectedRevenueWithoutVat(t.collected().base())
                    .collectedVatAmount(t.collected().vat())
                    .pendingRevenue(t.pending().total())
                    .pendingRevenueWithoutVat(t.pending().base())
                    .pendingVatAmount(t.pending().vat())
                    .paidCount(t.paidCount())
                    .pendingCount(t.pendingCount())
                    .overdueCount(t.overdueCount())
                    .expenses(expenses)
                    .expenseCount(expenseCount)
                    .netResult(t.collected().total().subtract(expenses))
                    .build());
        }

        return list;
    }

    // ------------------------------------------------------------------
    // Per-unit breakdowns
    // ------------------------------------------------------------------

    private List<UnitOccupancyDTO> getUnitSummaryList(List<StorageUnit> units, List<RentalAgreement> activeAgreements) {
        Map<Long, RentalAgreement> unitToAgreement = new HashMap<>();
        for (RentalAgreement ra : activeAgreements) {
            unitToAgreement.put(ra.getStorageUnit().getId(), ra);
        }

        Map<Long, BigDecimal> unitRevenues = new HashMap<>();
        for (Object[] row : paymentRepository.sumRevenueByStorageUnit()) {
            unitRevenues.put((Long) row[0], (BigDecimal) row[2]);
        }
        Map<Long, BigDecimal> unitExpenses = expenseService.sumExpensesByUnit();

        List<UnitOccupancyDTO> result = new ArrayList<>();
        for (StorageUnit unit : units) {
            boolean vat = unit.isVatApplicable();
            RentalAgreement active = unitToAgreement.get(unit.getId());
            Breakdown rate = VatUtils.breakdown(unit.getBaseMonthlyRate(), vat);
            Breakdown rev = VatUtils.breakdown(unitRevenues.getOrDefault(unit.getId(), BigDecimal.ZERO), vat);
            BigDecimal exp = unitExpenses.getOrDefault(unit.getId(), BigDecimal.ZERO);
            Breakdown rent = active != null && active.getMonthlyRent() != null
                    ? VatUtils.breakdown(active.getMonthlyRent(), vat) : null;

            result.add(UnitOccupancyDTO.builder()
                    .id(unit.getId())
                    .unitNumber(unit.getUnitNumber())
                    .name(unit.getName())
                    .kind(unit.getKind())
                    .vatApplicable(vat)
                    .sizeSquareMeters(unit.getSizeSquareMeters())
                    .status(unit.getStatus())
                    .location(unit.getLocation())
                    .storageGroupId(unit.getStorageGroup() != null ? unit.getStorageGroup().getId() : null)
                    .storageGroupName(unit.getStorageGroup() != null ? unit.getStorageGroup().getName() : null)
                    .currentClientName(active != null ? active.getClient().getFullName() : null)
                    .currentAgreementNumber(active != null ? active.getAgreementNumber() : null)
                    // Tarifa Base (con IVA y desglose)
                    .baseMonthlyRate(unit.getBaseMonthlyRate())
                    .baseMonthlyRateWithoutVat(rate.base())
                    .baseMonthlyRateVatAmount(rate.vat())
                    // Alquiler Actual
                    .actualMonthlyRent(active != null ? active.getMonthlyRent() : null)
                    .actualMonthlyRentWithoutVat(rent != null ? rent.base() : null)
                    .actualMonthlyRentVatAmount(rent != null ? rent.vat() : null)
                    // Ingresos acumulados
                    .totalRevenueGenerated(rev.total())
                    .totalRevenueGeneratedWithoutVat(rev.base())
                    .totalRevenueGeneratedVatAmount(rev.vat())
                    // Gastos imputados al trastero y resultado neto
                    .totalExpenses(exp)
                    .netResult(rev.total().subtract(exp))
                    .build());
        }

        return result;
    }

    public List<UnitRevenueDTO> getUnitRevenues() {
        return getUnitRevenues(null);
    }

    public List<UnitRevenueDTO> getUnitRevenues(Collection<Long> groupIdsParam) {
        Set<Long> groupIds = normalizeGroupIds(groupIdsParam);
        Set<Long> unitIds = groupIds == null ? null
                : unitsIn(groupIds).stream().map(StorageUnit::getId).collect(java.util.stream.Collectors.toSet());

        List<UnitRevenueDTO> list = new ArrayList<>();
        for (Object[] r : paymentRepository.sumRevenueByStorageUnit()) {
            Long unitId = (Long) r[0];
            if (unitIds != null && !unitIds.contains(unitId)) continue;
            list.add(new UnitRevenueDTO(unitId, (String) r[1], "", (BigDecimal) r[2]));
        }
        return list;
    }
}
