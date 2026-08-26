package com.storagemanager.storage_management;

import com.storagemanager.storage_management.dto.DashboardStatsDTO;
import com.storagemanager.storage_management.dto.ExpenseRequest;
import com.storagemanager.storage_management.model.Expense;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.model.enums.ExpenseCategory;
import com.storagemanager.storage_management.service.StorageUnitService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class ExpenseControllerTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private StorageUnitService storageUnitService;

    @Test
    void seededExpensesAreListedNewestFirst() {
        ResponseEntity<Expense[]> response = restTemplate.getForEntity("/api/expenses", Expense[].class);
        assertEquals(200, response.getStatusCode().value());
        Expense[] expenses = response.getBody();
        assertNotNull(expenses);
        assertTrue(expenses.length >= 52, "The 52 statement expenses should be seeded");
        for (int i = 1; i < expenses.length; i++) {
            assertFalse(expenses[i].getExpenseDate().isAfter(expenses[i - 1].getExpenseDate()),
                    "Expenses must be ordered by date descending");
        }
    }

    @Test
    void filtersByYearMonthAndCategory() {
        ResponseEntity<Expense[]> july2026 = restTemplate.getForEntity(
                "/api/expenses?year=2026&month=7", Expense[].class);
        assertNotNull(july2026.getBody());
        assertEquals(4, july2026.getBody().length, "July 2026 has 4 seeded expenses");
        for (Expense e : july2026.getBody()) {
            assertEquals(2026, e.getExpenseDate().getYear());
            assertEquals(7, e.getExpenseDate().getMonthValue());
        }

        ResponseEntity<Expense[]> repairs = restTemplate.getForEntity(
                "/api/expenses?category=REPARACIONES", Expense[].class);
        assertNotNull(repairs.getBody());
        assertTrue(repairs.getBody().length >= 3);
        for (Expense e : repairs.getBody()) {
            assertEquals(ExpenseCategory.REPARACIONES, e.getCategory());
        }
    }

    @Test
    void sortsAndFiltersByAmount() {
        ResponseEntity<Expense[]> byAmount = restTemplate.getForEntity(
                "/api/expenses?year=2025&sortBy=amount&direction=asc&minAmount=100&maxAmount=400", Expense[].class);
        assertEquals(200, byAmount.getStatusCode().value());
        Expense[] expenses = byAmount.getBody();
        assertNotNull(expenses);
        assertTrue(expenses.length > 0);
        for (int i = 0; i < expenses.length; i++) {
            assertTrue(expenses[i].getAmount().compareTo(new BigDecimal("100")) >= 0);
            assertTrue(expenses[i].getAmount().compareTo(new BigDecimal("400")) <= 0);
            if (i > 0) {
                assertTrue(expenses[i].getAmount().compareTo(expenses[i - 1].getAmount()) >= 0,
                        "Expenses must be ordered by amount ascending");
            }
        }

        ResponseEntity<Map> badSort = restTemplate.getForEntity("/api/expenses?sortBy=colour", Map.class);
        assertEquals(400, badSort.getStatusCode().value());
    }

    @Test
    void createUpdateAndDeleteUnitExpense() {
        StorageUnit unit = storageUnitService.getAllUnits().get(0);

        ExpenseRequest request = new ExpenseRequest();
        request.setAmount(new BigDecimal("75.50"));
        request.setDescription("Cambio de cerradura");
        request.setCategory(ExpenseCategory.REPARACIONES);
        request.setExpenseDate(LocalDate.of(2026, 8, 20));
        request.setStorageUnitId(unit.getId());

        ResponseEntity<Expense> created = restTemplate.postForEntity("/api/expenses", request, Expense.class);
        assertEquals(201, created.getStatusCode().value());
        Expense expense = created.getBody();
        assertNotNull(expense);
        assertNotNull(expense.getId());
        assertEquals(unit.getId(), expense.getStorageUnit().getId());
        assertEquals(0, new BigDecimal("75.50").compareTo(expense.getAmount()));

        // Listed under the unit
        ResponseEntity<Expense[]> byUnit = restTemplate.getForEntity(
                "/api/expenses?storageUnitId=" + unit.getId(), Expense[].class);
        assertNotNull(byUnit.getBody());
        assertTrue(List.of(byUnit.getBody()).stream().anyMatch(e -> e.getId().equals(expense.getId())));

        // Update: make it a general expense in another category
        request.setStorageUnitId(null);
        request.setCategory(ExpenseCategory.OTROS);
        request.setDescription("Cambio de cerradura (general)");
        restTemplate.put("/api/expenses/" + expense.getId(), request);
        ResponseEntity<Expense> updated = restTemplate.getForEntity("/api/expenses/" + expense.getId(), Expense.class);
        assertNotNull(updated.getBody());
        assertNull(updated.getBody().getStorageUnit());
        assertEquals(ExpenseCategory.OTROS, updated.getBody().getCategory());

        restTemplate.delete("/api/expenses/" + expense.getId());
        ResponseEntity<Map> gone = restTemplate.getForEntity("/api/expenses/" + expense.getId(), Map.class);
        assertEquals(404, gone.getStatusCode().value());
    }

    @Test
    void rejectsInvalidExpense() {
        ExpenseRequest request = new ExpenseRequest();
        request.setAmount(BigDecimal.ZERO);
        request.setDescription("");
        request.setExpenseDate(LocalDate.now());

        ResponseEntity<Map> response = restTemplate.postForEntity("/api/expenses", request, Map.class);
        assertEquals(400, response.getStatusCode().value());
        assertNotNull(response.getBody());
        Map<?, ?> errors = (Map<?, ?>) response.getBody().get("errors");
        assertNotNull(errors);
        assertTrue(errors.containsKey("amount"));
        assertTrue(errors.containsKey("description"));
        assertTrue(errors.containsKey("category"));
    }

    @Test
    void unknownStorageUnitIsRejected() {
        ExpenseRequest request = new ExpenseRequest();
        request.setAmount(new BigDecimal("10.00"));
        request.setDescription("Bombilla");
        request.setCategory(ExpenseCategory.OTROS);
        request.setExpenseDate(LocalDate.now());
        request.setStorageUnitId(999999L);

        ResponseEntity<Map> response = restTemplate.postForEntity("/api/expenses", request, Map.class);
        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void statisticsIncludeExpensesAndNetResult() {
        ResponseEntity<DashboardStatsDTO> response = restTemplate.getForEntity(
                "/api/statistics/dashboard", DashboardStatsDTO.class);
        assertEquals(200, response.getStatusCode().value());
        DashboardStatsDTO stats = response.getBody();
        assertNotNull(stats);

        assertTrue(stats.getTotalExpensesAllTime().compareTo(new BigDecimal("6173.70")) >= 0,
                "All seeded expenses (6.173,70 EUR) must be counted");
        assertEquals(0, stats.getNetResultAllTime()
                .compareTo(stats.getTotalRevenueAllTime().subtract(stats.getTotalExpensesAllTime())));
        assertEquals(0, stats.getCurrentMonthNetResult()
                .compareTo(stats.getCurrentMonthCollectedRevenue().subtract(stats.getCurrentMonthExpenses())));

        assertNotNull(stats.getExpensesByCategory());
        assertEquals(ExpenseCategory.values().length, stats.getExpensesByCategory().size());
        BigDecimal byCategoryTotal = stats.getExpensesByCategory().stream()
                .map(c -> c.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, byCategoryTotal.compareTo(stats.getTotalExpensesAllTime()));

        assertNotNull(stats.getRecentMonthlyRevenue());
        stats.getRecentMonthlyRevenue().forEach(m -> {
            assertNotNull(m.getExpenses());
            assertEquals(0, m.getNetResult().compareTo(m.getCollectedRevenue().subtract(m.getExpenses())));
        });
        stats.getUnitsSummary().forEach(u -> {
            assertNotNull(u.getTotalExpenses());
            assertEquals(0, u.getNetResult().compareTo(u.getTotalRevenueGenerated().subtract(u.getTotalExpenses())));
        });
    }

    @Test
    void rangeStatisticsSumExpensesInsideTheRange() {
        // July 2026: 227.89 + 125.78 + 203.55 + 14.71 = 571.93
        ResponseEntity<DashboardStatsDTO> response = restTemplate.getForEntity(
                "/api/statistics/range?startDate=2026-07-01&endDate=2026-07-31", DashboardStatsDTO.class);
        assertEquals(200, response.getStatusCode().value());
        DashboardStatsDTO stats = response.getBody();
        assertNotNull(stats);
        assertEquals(0, new BigDecimal("571.93").compareTo(stats.getCurrentMonthExpenses()));
        assertEquals(4, stats.getCurrentMonthExpenseCount());
        BigDecimal byCategoryTotal = stats.getExpensesByCategory().stream()
                .map(c -> c.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, byCategoryTotal.compareTo(stats.getCurrentMonthExpenses()));
    }
}
