package com.storagemanager.storage_management;

import com.storagemanager.storage_management.config.DataSeeder;
import com.storagemanager.storage_management.config.VatUtils;
import com.storagemanager.storage_management.dto.DashboardStatsDTO;
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
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class ApartmentUnitTest {

    @Autowired
    private TestRestTemplate restTemplate;

    private StorageUnit unitNumbered(String number) {
        ResponseEntity<StorageUnit[]> response = restTemplate.getForEntity("/api/storages", StorageUnit[].class);
        assertNotNull(response.getBody());
        return Arrays.stream(response.getBody())
                .filter(u -> number.equals(u.getUnitNumber()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Unit should be seeded: " + number));
    }

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
    void seededTrasterosSitInsideTheStorageLocal() {
        ResponseEntity<StorageUnit[]> response = restTemplate.getForEntity("/api/storages?kind=STORAGE_UNIT", StorageUnit[].class);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().length >= 9);
        for (StorageUnit unit : response.getBody()) {
            assertEquals(UnitKind.STORAGE_UNIT, unit.getKind());
            assertTrue(unit.isVatApplicable());
            if (unit.getUnitNumber().matches("[1-9]")) {
                assertNotNull(unit.getParent(), "Trastero " + unit.getUnitNumber() + " is inside the local");
                assertEquals(DataSeeder.STORAGE_PREMISES_NUMBER, unit.getParent().getUnitNumber());
                assertEquals(DataSeeder.STORAGE_PREMISES_NUMBER, unit.getRoot().unitNumber());
            }
        }

        // Los dos bajos son locales; sólo el que agrupa trasteros queda fuera del inventario
        ResponseEntity<StorageUnit[]> locales = restTemplate.getForEntity("/api/storages?kind=PREMISES", StorageUnit[].class);
        assertNotNull(locales.getBody());
        assertEquals(2, Arrays.stream(locales.getBody()).filter(u -> u.getUnitNumber().matches("B[DT]")).count());
        assertTrue(Arrays.stream(locales.getBody()).allMatch(StorageUnit::isPremises));

        ResponseEntity<DashboardStatsDTO> all = restTemplate.getForEntity("/api/statistics/dashboard", DashboardStatsDTO.class);
        assertNotNull(all.getBody());
        assertEquals(all.getBody().getTotalUnits(),
                all.getBody().getStorageUnitCount() + all.getBody().getApartmentCount() + all.getBody().getPremisesCount());
        // El bajo trasero está vacío: se alquila como una unidad más y cuenta.
        // El delantero agrupa los nueve trasteros, así que no.
        assertEquals(1, all.getBody().getPremisesCount());
        assertTrue(all.getBody().getUnitsSummary().stream().anyMatch(u -> "BT".equals(u.getUnitNumber())));
        assertTrue(all.getBody().getUnitsSummary().stream().noneMatch(u -> DataSeeder.STORAGE_PREMISES_NUMBER.equals(u.getUnitNumber())));

        // Filtering by the local covers its 9 trasteros
        StorageUnit local = unitNumbered(DataSeeder.STORAGE_PREMISES_NUMBER);
        ResponseEntity<DashboardStatsDTO> storage = restTemplate.getForEntity(
                "/api/statistics/dashboard?rootIds=" + local.getId(), DashboardStatsDTO.class);
        assertNotNull(storage.getBody());
        assertEquals(9, storage.getBody().getTotalUnits());
        assertTrue(storage.getBody().getTotalExpensesAllTime().compareTo(BigDecimal.ZERO) > 0,
                "The storage-account expenses are attributed to the local and count for its root");
    }

    @Test
    void apartmentExpensesAreSeededOnTheFlats() {
        StorageUnit flat3d = unitNumbered("3D");
        ResponseEntity<Expense[]> response = restTemplate.getForEntity(
                "/api/expenses?rootId=" + flat3d.getId(), Expense[].class);
        assertEquals(200, response.getStatusCode().value());
        Expense[] expenses = response.getBody();
        assertNotNull(expenses);
        // 27 own expenses plus half of the 75 shared flats-statement entries
        assertTrue(expenses.length >= 100, "3D should carry its own expenses plus its half of the shared ones");
        for (Expense e : expenses) {
            assertNotNull(e.getStorageUnit());
            assertEquals("3D", e.getStorageUnit().getUnitNumber());
        }
        // Community fee: 20 EUR per apartment each month
        assertTrue(Arrays.stream(expenses).anyMatch(e ->
                e.getCategory() == ExpenseCategory.COMUNIDAD && e.getAmount().compareTo(new BigDecimal("20")) == 0));
        // IBI of 3D, July 2026, booked on the flat itself
        assertTrue(Arrays.stream(expenses).anyMatch(e ->
                e.getExpenseDate().equals(java.time.LocalDate.of(2026, 7, 7))
                        && e.getCategory() == ExpenseCategory.TRIBUTOS
                        && e.getAmount().compareTo(new BigDecimal("182.03")) == 0));
        // A flats-statement entry shared by both flats is split evenly: the same date and half amounts on 3D and 3E
        StorageUnit flat3e = unitNumbered("3E");
        Expense[] other = restTemplate.getForEntity("/api/expenses?rootId=" + flat3e.getId(), Expense[].class).getBody();
        assertNotNull(other);
        Expense shared = Arrays.stream(expenses).filter(e -> e.getDescription().contains("reparto 3D/3E")).findFirst().orElseThrow();
        Expense twin = Arrays.stream(other)
                .filter(e -> e.getDescription().equals(shared.getDescription()) && e.getExpenseDate().equals(shared.getExpenseDate()))
                .findFirst().orElseThrow();
        assertTrue(shared.getAmount().subtract(twin.getAmount()).abs().compareTo(new BigDecimal("0.01")) <= 0,
                "Both halves of a shared expense are equal (up to the rounding cent): " + shared.getAmount() + " / " + twin.getAmount());
        // Reimbursed outflows (tenant electricity refunds, ABONO NOMINA) are not expenses
        assertTrue(Arrays.stream(expenses).noneMatch(e ->
                e.getAmount().compareTo(new BigDecimal("2450")) == 0
                        || e.getAmount().compareTo(new BigDecimal("130.23")) == 0));
    }

    @Test
    void apartmentsAreVatExemptEverywhere() {
        StorageUnitRequest request = new StorageUnitRequest();
        request.setUnitNumber("APT-1");
        request.setName("Apartamento 1");
        request.setKind(UnitKind.APARTMENT);
        request.setSizeSquareMeters(55.0);
        request.setBaseMonthlyRate(new BigDecimal("605.00"));
        request.setStatus(UnitStatus.AVAILABLE);

        ResponseEntity<StorageUnit> created = restTemplate.postForEntity("/api/storages", request, StorageUnit.class);
        assertEquals(201, created.getStatusCode().value());
        assertNotNull(created.getBody());
        assertEquals(UnitKind.APARTMENT, created.getBody().getKind());
        assertFalse(created.getBody().isVatApplicable());
        assertNull(created.getBody().getParent());
        Long apartmentId = created.getBody().getId();
        assertEquals(apartmentId, created.getBody().getRootId(), "A stand-alone unit is its own root");

        // Kind filter
        ResponseEntity<StorageUnit[]> apartments = restTemplate.getForEntity("/api/storages?kind=APARTMENT", StorageUnit[].class);
        assertNotNull(apartments.getBody());
        assertTrue(Arrays.stream(apartments.getBody()).anyMatch(u -> u.getId().equals(apartmentId)));
        assertTrue(Arrays.stream(apartments.getBody()).noneMatch(StorageUnit::isVatApplicable));

        // Unit history: the price history entry carries no VAT
        ResponseEntity<UnitHistoryDTO> history = restTemplate.getForEntity("/api/storages/" + apartmentId + "/history", UnitHistoryDTO.class);
        assertEquals(200, history.getStatusCode().value());
        assertNotNull(history.getBody());
        assertEquals(1, history.getBody().getPriceHistory().size());
        UnitHistoryDTO.PriceEntry price = history.getBody().getPriceHistory().get(0);
        assertEquals(0, new BigDecimal("605.00").compareTo(price.getMonthlyPriceWithoutVat()));
        assertEquals(0, BigDecimal.ZERO.compareTo(price.getMonthlyPriceVatAmount()));

        // Dashboard restricted to the apartment (its own root): potential revenue has no VAT quota
        ResponseEntity<DashboardStatsDTO> stats = restTemplate.getForEntity(
                "/api/statistics/dashboard?rootIds=" + apartmentId, DashboardStatsDTO.class);
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
        assertEquals(apartmentId, summary.getRootUnitId());
        assertNull(summary.getParentUnitId());
        assertEquals(0, new BigDecimal("605.00").compareTo(summary.getBaseMonthlyRateWithoutVat()));
        assertEquals(0, BigDecimal.ZERO.compareTo(summary.getBaseMonthlyRateVatAmount()));

        // The global dashboard still splits the storage units' VAT, and counts both kinds
        ResponseEntity<DashboardStatsDTO> all = restTemplate.getForEntity("/api/statistics/dashboard", DashboardStatsDTO.class);
        assertNotNull(all.getBody());
        assertTrue(all.getBody().getMonthlyPotentialVatAmount().compareTo(BigDecimal.ZERO) > 0);
        assertTrue(all.getBody().getApartmentCount() >= 1);
        assertEquals(all.getBody().getTotalUnits(),
                all.getBody().getStorageUnitCount() + all.getBody().getApartmentCount() + all.getBody().getPremisesCount());
    }

    @Test
    void unitsCanBePlacedInsideALocalButNotInACycle() {
        StorageUnit local = unitNumbered(DataSeeder.STORAGE_PREMISES_NUMBER);

        StorageUnitRequest inside = new StorageUnitRequest();
        inside.setUnitNumber("T-NEW");
        inside.setName("Trastero nuevo");
        inside.setParentId(local.getId());
        inside.setSizeSquareMeters(4.0);
        inside.setBaseMonthlyRate(new BigDecimal("40.00"));
        ResponseEntity<StorageUnit> created = restTemplate.postForEntity("/api/storages", inside, StorageUnit.class);
        assertEquals(201, created.getStatusCode().value());
        assertNotNull(created.getBody());
        assertEquals(local.getId(), created.getBody().getParent().getId());
        assertEquals(local.getId(), created.getBody().getRootId());

        // The local cannot be moved inside the unit it contains, nor inside itself
        StorageUnitRequest moveLocal = new StorageUnitRequest();
        moveLocal.setUnitNumber(local.getUnitNumber());
        moveLocal.setName(local.getName());
        moveLocal.setKind(UnitKind.PREMISES);
        moveLocal.setParentId(created.getBody().getId());
        moveLocal.setSizeSquareMeters(local.getSizeSquareMeters());
        moveLocal.setBaseMonthlyRate(local.getBaseMonthlyRate());
        ResponseEntity<java.util.Map> cycle = restTemplate.exchange("/api/storages/" + local.getId(),
                org.springframework.http.HttpMethod.PUT, new org.springframework.http.HttpEntity<>(moveLocal), java.util.Map.class);
        assertEquals(400, cycle.getStatusCode().value());
        moveLocal.setParentId(local.getId());
        ResponseEntity<java.util.Map> self = restTemplate.exchange("/api/storages/" + local.getId(),
                org.springframework.http.HttpMethod.PUT, new org.springframework.http.HttpEntity<>(moveLocal), java.util.Map.class);
        assertEquals(400, self.getStatusCode().value());

        // A local with units inside cannot be deleted; the new trastero can
        ResponseEntity<java.util.Map> blocked = restTemplate.exchange("/api/storages/" + local.getId(),
                org.springframework.http.HttpMethod.DELETE, null, java.util.Map.class);
        assertEquals(400, blocked.getStatusCode().value());
        ResponseEntity<Void> deleted = restTemplate.exchange("/api/storages/" + created.getBody().getId(),
                org.springframework.http.HttpMethod.DELETE, null, Void.class);
        assertEquals(204, deleted.getStatusCode().value());
    }
}
