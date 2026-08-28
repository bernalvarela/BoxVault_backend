package com.storagemanager.storage_management;

import com.storagemanager.storage_management.config.VatUtils;
import com.storagemanager.storage_management.dto.DashboardStatsDTO;
import com.storagemanager.storage_management.dto.StorageGroupDTO;
import com.storagemanager.storage_management.dto.StorageGroupRequest;
import com.storagemanager.storage_management.dto.StorageUnitRequest;
import com.storagemanager.storage_management.dto.UnitHistoryDTO;
import com.storagemanager.storage_management.dto.UnitOccupancyDTO;
import com.storagemanager.storage_management.model.Expense;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.model.enums.ExpenseCategory;
import com.storagemanager.storage_management.model.enums.UnitKind;
import com.storagemanager.storage_management.model.enums.UnitStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class ApartmentUnitTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void vatBreakdownDependsOnVatApplicability() {
        VatUtils.Breakdown withVat = VatUtils.breakdown(new BigDecimal("121.00"), true);
        assertEquals(0, new BigDecimal("100.00").compareTo(withVat.base()));
        assertEquals(0, new BigDecimal("21.00").compareTo(withVat.vat()));

        VatUtils.Breakdown exempt = VatUtils.breakdown(new BigDecimal("121.00"), false);
        assertEquals(0, new BigDecimal("121.00").compareTo(exempt.base()));
        assertEquals(0, BigDecimal.ZERO.compareTo(exempt.vat()));

        VatUtils.Breakdown mixed = withVat.plus(exempt);
        assertEquals(0, new BigDecimal("242.00").compareTo(mixed.total()));
        assertEquals(0, new BigDecimal("221.00").compareTo(mixed.base()));
        assertEquals(0, new BigDecimal("21.00").compareTo(mixed.vat()));
    }

    @Test
    void seededUnitsAreStorageUnitsWithVat() {
        ResponseEntity<StorageUnit[]> response = restTemplate.getForEntity("/api/storages?kind=STORAGE_UNIT", StorageUnit[].class);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().length >= 9);
        for (StorageUnit unit : response.getBody()) {
            assertEquals(UnitKind.STORAGE_UNIT, unit.getKind());
            assertTrue(unit.isVatApplicable());
        }
    }

    @Test
    void apartmentExpensesAreSeededIntoTheirGroup() {
        ResponseEntity<StorageGroupDTO[]> groups = restTemplate.getForEntity("/api/storage-groups", StorageGroupDTO[].class);
        assertNotNull(groups.getBody());
        StorageGroupDTO pisos = java.util.Arrays.stream(groups.getBody())
                .filter(g -> "Pisos Pasaxe 29".equals(g.getName()))
                .findFirst().orElseThrow();

        ResponseEntity<Expense[]> response = restTemplate.getForEntity(
                "/api/expenses?storageGroupId=" + pisos.getId(), Expense[].class);
        assertEquals(200, response.getStatusCode().value());
        Expense[] expenses = response.getBody();
        assertNotNull(expenses);
        assertTrue(expenses.length >= 130, "The 130 flats-statement expenses (jul 2024 - ago 2026) should be seeded");
        for (Expense e : expenses) {
            if (e.getStorageUnit() != null) {
                assertNull(e.getStorageGroup());
                assertEquals(pisos.getId(), e.getStorageUnit().getStorageGroup().getId());
            } else {
                assertNotNull(e.getStorageGroup());
                assertEquals(pisos.getId(), e.getStorageGroup().getId());
            }
        }
        // Community fee: 20 EUR per apartment each month
        assertTrue(java.util.Arrays.stream(expenses).anyMatch(e ->
                e.getCategory() == ExpenseCategory.COMUNIDAD
                        && e.getStorageUnit() != null && "3D".equals(e.getStorageUnit().getUnitNumber())
                        && e.getAmount().compareTo(new BigDecimal("20")) == 0));
        // IBI of the two flats, July 2026
        assertTrue(java.util.Arrays.stream(expenses).anyMatch(e ->
                e.getExpenseDate().equals(java.time.LocalDate.of(2026, 7, 7))
                        && e.getAmount().compareTo(new BigDecimal("182.03")) == 0));
        // Reimbursed outflows (tenant electricity refunds, ABONO NOMINA) are not expenses
        assertTrue(java.util.Arrays.stream(expenses).noneMatch(e ->
                e.getAmount().compareTo(new BigDecimal("2450")) == 0
                        || e.getAmount().compareTo(new BigDecimal("130.23")) == 0));
    }

    @Test
    void apartmentsAreVatExemptEverywhere() {
        // Own group so the dashboard can be checked in isolation
        StorageGroupRequest groupRequest = new StorageGroupRequest();
        groupRequest.setName("Pisos de prueba");
        ResponseEntity<StorageGroupDTO> group = restTemplate.postForEntity("/api/storage-groups", groupRequest, StorageGroupDTO.class);
        assertEquals(201, group.getStatusCode().value());
        assertNotNull(group.getBody());
        Long groupId = group.getBody().getId();

        StorageUnitRequest request = new StorageUnitRequest();
        request.setUnitNumber("APT-1");
        request.setName("Apartamento 1");
        request.setKind(UnitKind.APARTMENT);
        request.setStorageGroupId(groupId);
        request.setSizeSquareMeters(55.0);
        request.setBaseMonthlyRate(new BigDecimal("605.00"));
        request.setStatus(UnitStatus.AVAILABLE);

        ResponseEntity<StorageUnit> created = restTemplate.postForEntity("/api/storages", request, StorageUnit.class);
        assertEquals(201, created.getStatusCode().value());
        assertNotNull(created.getBody());
        assertEquals(UnitKind.APARTMENT, created.getBody().getKind());
        assertFalse(created.getBody().isVatApplicable());
        Long apartmentId = created.getBody().getId();

        // Kind filter
        ResponseEntity<StorageUnit[]> apartments = restTemplate.getForEntity("/api/storages?kind=APARTMENT", StorageUnit[].class);
        assertNotNull(apartments.getBody());
        assertTrue(java.util.Arrays.stream(apartments.getBody()).anyMatch(u -> u.getId().equals(apartmentId)));
        assertTrue(java.util.Arrays.stream(apartments.getBody()).noneMatch(StorageUnit::isVatApplicable));

        // Unit history: the price history entry carries no VAT
        ResponseEntity<UnitHistoryDTO> history = restTemplate.getForEntity("/api/storages/" + apartmentId + "/history", UnitHistoryDTO.class);
        assertEquals(200, history.getStatusCode().value());
        assertNotNull(history.getBody());
        assertEquals(1, history.getBody().getPriceHistory().size());
        UnitHistoryDTO.PriceEntry price = history.getBody().getPriceHistory().get(0);
        assertEquals(0, new BigDecimal("605.00").compareTo(price.getMonthlyPriceWithoutVat()));
        assertEquals(0, BigDecimal.ZERO.compareTo(price.getMonthlyPriceVatAmount()));

        // Dashboard restricted to the apartment's group: potential revenue has no VAT quota
        ResponseEntity<DashboardStatsDTO> stats = restTemplate.getForEntity(
                "/api/statistics/dashboard?groupIds=" + groupId, DashboardStatsDTO.class);
        assertEquals(200, stats.getStatusCode().value());
        DashboardStatsDTO body = stats.getBody();
        assertNotNull(body);
        assertEquals(1, body.getTotalUnits());
        assertEquals(1, body.getApartmentCount());
        assertEquals(0, body.getStorageUnitCount());
        assertEquals(0, new BigDecimal("605.00").compareTo(body.getMonthlyPotentialRevenue()));
        assertEquals(0, new BigDecimal("605.00").compareTo(body.getMonthlyPotentialRevenueWithoutVat()));
        assertEquals(0, BigDecimal.ZERO.compareTo(body.getMonthlyPotentialVatAmount()));

        UnitOccupancyDTO summary = body.getUnitsSummary().get(0);
        assertEquals(UnitKind.APARTMENT, summary.getKind());
        assertFalse(summary.isVatApplicable());
        assertEquals(0, new BigDecimal("605.00").compareTo(summary.getBaseMonthlyRateWithoutVat()));
        assertEquals(0, BigDecimal.ZERO.compareTo(summary.getBaseMonthlyRateVatAmount()));

        // The global dashboard still splits the storage units' VAT, and counts both kinds
        ResponseEntity<DashboardStatsDTO> all = restTemplate.getForEntity("/api/statistics/dashboard", DashboardStatsDTO.class);
        assertNotNull(all.getBody());
        assertTrue(all.getBody().getMonthlyPotentialVatAmount().compareTo(BigDecimal.ZERO) > 0);
        assertTrue(all.getBody().getApartmentCount() >= 1);
        assertEquals(all.getBody().getTotalUnits(), all.getBody().getStorageUnitCount() + all.getBody().getApartmentCount());
    }
}
