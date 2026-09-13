package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.dto.ExpenseCategorySummaryDTO;
import com.storagemanager.storage_management.dto.ExpenseRequest;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.Expense;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.model.enums.ExpenseCategory;
import com.storagemanager.storage_management.repository.ExpenseRepository;
import com.storagemanager.storage_management.repository.StorageUnitRepository;
import com.storagemanager.storage_management.security.UnitScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ExpenseService {

    private final ExpenseRepository expenseRepository;
    private final StorageUnitRepository storageUnitRepository;
    private final UnitScope unitScope;

    /** The root unit (local / flat) an expense counts towards; null for a general expense. */
    public static Long rootOf(Expense expense) {
        return expense.getStorageUnit() == null ? null : expense.getStorageUnit().getRootId();
    }

    /**
     * True when the expense belongs to a unit under one of the given root units. A
     * null {@code rootIds} means "no filter" and matches every expense, general ones
     * included; with a filter, general expenses (no unit) never match.
     */
    public static boolean belongsToRoots(Expense expense, Set<Long> rootIds) {
        if (rootIds == null) return true;
        Long root = rootOf(expense);
        return root != null && rootIds.contains(root);
    }

    public List<Expense> getAllExpenses() {
        return unitScope.filterByUnit(expenseRepository.findAllByOrderByExpenseDateDesc(), Expense::getStorageUnit);
    }

    public Expense getExpenseById(Long id) {
        Expense expense = expenseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Expense not found with id: " + id));
        unitScope.requireAccessible(expense.getStorageUnit());
        return expense;
    }

    /**
     * Optional listing filters; a year without a month means the whole year.
     * {@code storageUnitId} matches the expenses of exactly that unit; {@code rootId}
     * those of every unit under that root (a local and the trasteros inside it).
     */
    public record Filter(Integer year, Integer month, Long storageUnitId, Long rootId,
                         ExpenseCategory category, LocalDate startDate, LocalDate endDate,
                         BigDecimal minAmount, BigDecimal maxAmount) {
    }

    /** Sort keys accepted by the listing. */
    public enum SortBy {
        expenseDate, amount, category, description, storageUnit;

        /** Case-insensitive lookup; null/blank falls back to expenseDate. */
        public static SortBy from(String value) {
            if (value == null || value.isBlank()) return expenseDate;
            for (SortBy s : values()) {
                if (s.name().equalsIgnoreCase(value.trim())) return s;
            }
            throw new BadRequestException("Unknown sortBy '" + value + "'. Allowed: "
                    + java.util.Arrays.toString(values()));
        }

        public Comparator<Expense> comparator() {
            return switch (this) {
                case expenseDate -> Comparator.comparing(Expense::getExpenseDate);
                case amount -> Comparator.comparing(Expense::getAmount);
                case category -> Comparator.comparing(Expense::getCategory);
                case description -> Comparator.comparing(Expense::getDescription, String.CASE_INSENSITIVE_ORDER);
                // General expenses (no unit) sort last
                case storageUnit -> Comparator.comparing(
                        (Expense e) -> e.getStorageUnit() == null ? null : e.getStorageUnit().getUnitNumber(),
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            };
        }
    }

    public List<Expense> searchExpenses(Filter filter, SortBy sortBy, boolean ascending) {
        List<Expense> result;
        if (filter.startDate() != null || filter.endDate() != null) {
            // Open-ended bounds that stay inside the JDBC/H2 date range
            LocalDate from = filter.startDate() != null ? filter.startDate() : LocalDate.of(1900, 1, 1);
            LocalDate to = filter.endDate() != null ? filter.endDate() : LocalDate.of(2999, 12, 31);
            result = expenseRepository.findByExpenseDateBetween(from, to);
        } else if (filter.year() != null && filter.month() != null) {
            YearMonth ym = YearMonth.of(filter.year(), filter.month());
            result = expenseRepository.findByExpenseDateBetween(ym.atDay(1), ym.atEndOfMonth());
        } else if (filter.year() != null) {
            result = expenseRepository.findByExpenseDateBetween(
                    LocalDate.of(filter.year(), 1, 1), LocalDate.of(filter.year(), 12, 31));
        } else if (filter.storageUnitId() != null) {
            result = expenseRepository.findByStorageUnitId(filter.storageUnitId());
        } else if (filter.category() != null) {
            result = expenseRepository.findByCategory(filter.category());
        } else {
            result = expenseRepository.findAll();
        }

        Comparator<Expense> order = (sortBy == null ? SortBy.expenseDate : sortBy).comparator();
        if (!ascending) order = order.reversed();
        // Stable tie-break: newest first, then highest id
        order = order.thenComparing(Expense::getExpenseDate, Comparator.reverseOrder())
                .thenComparing(Expense::getId, Comparator.reverseOrder());

        return unitScope.filterByUnit(result, Expense::getStorageUnit).stream()
                .filter(e -> filter.storageUnitId() == null
                        || (e.getStorageUnit() != null && filter.storageUnitId().equals(e.getStorageUnit().getId())))
                .filter(e -> filter.rootId() == null || belongsToRoots(e, Set.of(filter.rootId())))
                .filter(e -> filter.category() == null || e.getCategory() == filter.category())
                .filter(e -> filter.minAmount() == null || e.getAmount().compareTo(filter.minAmount()) >= 0)
                .filter(e -> filter.maxAmount() == null || e.getAmount().compareTo(filter.maxAmount()) <= 0)
                .sorted(order)
                .toList();
    }

    @Transactional
    public Expense createExpense(ExpenseRequest request) {
        Expense expense = new Expense();
        applyRequest(expense, request);
        return expenseRepository.save(expense);
    }

    @Transactional
    public Expense updateExpense(Long id, ExpenseRequest request) {
        Expense expense = getExpenseById(id);
        applyRequest(expense, request);
        return expenseRepository.save(expense);
    }

    @Transactional
    public void deleteExpense(Long id) {
        expenseRepository.delete(getExpenseById(id));
    }

    private void applyRequest(Expense expense, ExpenseRequest request) {
        // Todo gasto va contra una unidad: es lo que permite repartirlo por
        // inmueble, imputarlo en los impuestos y saber a quién le toca verlo.
        if (request.getStorageUnitId() == null) {
            throw new BadRequestException("El gasto tiene que ir a una unidad");
        }
        StorageUnit unit = storageUnitRepository.findById(request.getStorageUnitId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Storage unit not found with id: " + request.getStorageUnitId()));
        unitScope.requireAccessible(unit);
        expense.setStorageUnit(unit);
        expense.setAmount(request.getAmount());
        // El IVA soportado va dentro del importe pagado: no puede ser mayor que él.
        BigDecimal vat = request.getVatAmount();
        if (vat != null && vat.compareTo(request.getAmount()) > 0) {
            throw new BadRequestException("El IVA soportado no puede ser mayor que el importe del gasto");
        }
        expense.setVatAmount(vat != null && vat.signum() != 0 ? vat : null);
        expense.setDescription(request.getDescription().trim());
        expense.setCategory(request.getCategory());
        expense.setExpenseDate(request.getExpenseDate());
    }

    // ---- Aggregations used by the statistics ----
    // Every aggregation accepts an optional set of root-unit ids: null means "everything"
    // (the SQL aggregate is used); otherwise the expenses are loaded and filtered by
    // root in memory (see belongsToRoots), which is fine at this data volume.

    public BigDecimal sumTotalExpenses() {
        return sumTotalExpenses(null);
    }

    public BigDecimal sumTotalExpenses(Set<Long> rootIds) {
        if (canAggregateInSql(rootIds)) return nz(expenseRepository.sumTotalExpenses());
        return total(expensesIn(null, null, rootIds));
    }

    public BigDecimal sumExpensesBetween(LocalDate start, LocalDate end) {
        return sumExpensesBetween(start, end, null);
    }

    public BigDecimal sumExpensesBetween(LocalDate start, LocalDate end, Set<Long> rootIds) {
        if (canAggregateInSql(rootIds)) return nz(expenseRepository.sumExpensesBetween(start, end));
        return total(expensesIn(start, end, rootIds));
    }

    public long countExpensesBetween(LocalDate start, LocalDate end) {
        return countExpensesBetween(start, end, null);
    }

    public long countExpensesBetween(LocalDate start, LocalDate end, Set<Long> rootIds) {
        if (canAggregateInSql(rootIds)) return expenseRepository.countExpensesBetween(start, end);
        return expensesIn(start, end, rootIds).size();
    }

    public BigDecimal sumExpensesForMonth(YearMonth ym) {
        return sumExpensesForMonth(ym, null);
    }

    public BigDecimal sumExpensesForMonth(YearMonth ym, Set<Long> rootIds) {
        return sumExpensesBetween(ym.atDay(1), ym.atEndOfMonth(), rootIds);
    }

    public long countExpensesForMonth(YearMonth ym) {
        return countExpensesForMonth(ym, null);
    }

    public long countExpensesForMonth(YearMonth ym, Set<Long> rootIds) {
        return countExpensesBetween(ym.atDay(1), ym.atEndOfMonth(), rootIds);
    }

    /** Earliest expense date under the given roots (null = everything); null when there are none. */
    public LocalDate firstExpenseDate(Set<Long> rootIds) {
        return expensesIn(null, null, rootIds).stream()
                .map(Expense::getExpenseDate)
                .min(java.util.Comparator.naturalOrder())
                .orElse(null);
    }

    /** Latest expense date under the given roots (null = everything); null when there are none. */
    public LocalDate lastExpenseDate(Set<Long> rootIds) {
        return expensesIn(null, null, rootIds).stream()
                .map(Expense::getExpenseDate)
                .max(java.util.Comparator.naturalOrder())
                .orElse(null);
    }

    /** Expenses in [start, end] (both null = all time) that belong to the given roots. */
    private List<Expense> expensesIn(LocalDate start, LocalDate end, Set<Long> rootIds) {
        List<Expense> list = (start == null || end == null)
                ? expenseRepository.findAll()
                : expenseRepository.findByExpenseDateBetween(start, end);
        // El ámbito va antes que el filtro por inmueble: lo que no es del usuario
        // no suma en ningún total suyo.
        return unitScope.filterByUnit(list, Expense::getStorageUnit).stream()
                .filter(e -> belongsToRoots(e, rootIds))
                .toList();
    }

    /**
     * Si se puede resolver el total con un SUM en la base de datos. Con el ámbito
     * puesto no: el SUM cuenta todos los gastos, también los de unidades que el
     * usuario no ve, y entonces sus totales dirían de más.
     */
    private boolean canAggregateInSql(Set<Long> rootIds) {
        return rootIds == null && unitScope.isUnrestricted();
    }

    private static BigDecimal total(List<Expense> expenses) {
        return expenses.stream()
                .map(Expense::getAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal sumExpensesForUnit(Long unitId) {
        if (!unitScope.isAccessible(unitId)) return BigDecimal.ZERO;
        return nz(expenseRepository.sumExpensesForUnit(unitId));
    }

    /** Total expenses attributed to each unit, keyed by unit id (units without expenses are absent). */
    public Map<Long, BigDecimal> sumExpensesByUnit() {
        Set<Long> accessible = unitScope.accessibleUnitIds();
        Map<Long, BigDecimal> map = new HashMap<>();
        for (Object[] row : expenseRepository.sumExpensesByStorageUnit()) {
            Long unitId = (Long) row[0];
            if (accessible != null && !accessible.contains(unitId)) continue;
            map.put(unitId, nz((BigDecimal) row[1]));
        }
        return map;
    }

    /**
     * Total per category. Pass null dates for the all-time breakdown. Every category is
     * present in the result (zero when nothing was spent), in enum order.
     */
    public List<ExpenseCategorySummaryDTO> summarizeByCategory(LocalDate start, LocalDate end) {
        return summarizeByCategory(start, end, null);
    }

    /** Same as {@link #summarizeByCategory(LocalDate, LocalDate)} restricted to the given roots (null = all). */
    public List<ExpenseCategorySummaryDTO> summarizeByCategory(LocalDate start, LocalDate end, Set<Long> rootIds) {
        Map<ExpenseCategory, BigDecimal> amounts = new EnumMap<>(ExpenseCategory.class);
        Map<ExpenseCategory, Long> counts = new EnumMap<>(ExpenseCategory.class);

        if (canAggregateInSql(rootIds)) {
            List<Object[]> rows = (start == null || end == null)
                    ? expenseRepository.sumExpensesByCategory()
                    : expenseRepository.sumExpensesByCategoryBetween(start, end);
            for (Object[] r : rows) {
                amounts.put((ExpenseCategory) r[0], nz((BigDecimal) r[1]));
                counts.put((ExpenseCategory) r[0], ((Number) r[2]).longValue());
            }
        } else {
            for (Expense e : expensesIn(start, end, rootIds)) {
                amounts.merge(e.getCategory(), nz(e.getAmount()), BigDecimal::add);
                counts.merge(e.getCategory(), 1L, Long::sum);
            }
        }

        List<ExpenseCategorySummaryDTO> list = new ArrayList<>();
        for (ExpenseCategory cat : ExpenseCategory.values()) {
            list.add(ExpenseCategorySummaryDTO.builder()
                    .category(cat)
                    .amount(amounts.getOrDefault(cat, BigDecimal.ZERO))
                    .count(counts.getOrDefault(cat, 0L))
                    .build());
        }
        return list;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
