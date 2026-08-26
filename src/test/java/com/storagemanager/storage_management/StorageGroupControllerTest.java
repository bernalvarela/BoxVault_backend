package com.storagemanager.storage_management;

import com.storagemanager.storage_management.config.DataSeeder;
import com.storagemanager.storage_management.dto.DashboardStatsDTO;
import com.storagemanager.storage_management.dto.StorageGroupDTO;
import com.storagemanager.storage_management.dto.StorageGroupRequest;
import com.storagemanager.storage_management.dto.StorageUnitRequest;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.model.enums.UnitStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class StorageGroupControllerTest {

    @Autowired
    private TestRestTemplate restTemplate;

    private StorageGroupDTO defaultGroup() {
        ResponseEntity<StorageGroupDTO[]> response = restTemplate.getForEntity("/api/storage-groups", StorageGroupDTO[].class);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        return Arrays.stream(response.getBody())
                .filter(g -> DataSeeder.DEFAULT_GROUP_NAME.equals(g.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Default group should be seeded"));
    }

    private StorageGroupDTO createGroup(String name) {
        StorageGroupRequest request = new StorageGroupRequest();
        request.setName(name);
        request.setDescription("test group");
        ResponseEntity<StorageGroupDTO> response = restTemplate.postForEntity("/api/storage-groups", request, StorageGroupDTO.class);
        assertEquals(201, response.getStatusCode().value());
        assertNotNull(response.getBody());
        return response.getBody();
    }

    @Test
    void seededUnitsBelongToTheDefaultGroup() {
        StorageGroupDTO group = defaultGroup();
        assertEquals(9, group.getUnitCount(), "The 9 seeded trasteros belong to the default group");

        ResponseEntity<StorageUnit[]> units = restTemplate.getForEntity("/api/storages", StorageUnit[].class);
        assertNotNull(units.getBody());
        // Other tests may add units in other groups; the seeded ones are numbered "1".."9"
        long seeded = 0;
        for (StorageUnit unit : units.getBody()) {
            assertNotNull(unit.getStorageGroup(), "Unit " + unit.getUnitNumber() + " must have a group");
            if (unit.getUnitNumber().matches("[1-9]")) {
                assertEquals(group.getId(), unit.getStorageGroup().getId(),
                        "Seeded unit " + unit.getUnitNumber() + " must be in the default group");
                seeded++;
            }
        }
        assertEquals(9, seeded);
    }

    @Test
    void groupCrudAndDuplicateNameValidation() {
        StorageGroupDTO created = createGroup("Grupo CRUD");
        assertEquals("Grupo CRUD", created.getName());
        assertEquals(0, created.getUnitCount());

        // Duplicate name (case-insensitive) is rejected
        StorageGroupRequest duplicate = new StorageGroupRequest();
        duplicate.setName("grupo crud");
        ResponseEntity<Map> dupResponse = restTemplate.postForEntity("/api/storage-groups", duplicate, Map.class);
        assertEquals(400, dupResponse.getStatusCode().value());

        // Blank name is rejected by validation
        StorageGroupRequest blank = new StorageGroupRequest();
        blank.setName("   ");
        ResponseEntity<Map> blankResponse = restTemplate.postForEntity("/api/storage-groups", blank, Map.class);
        assertEquals(400, blankResponse.getStatusCode().value());

        // Rename
        StorageGroupRequest rename = new StorageGroupRequest();
        rename.setName("Grupo CRUD renombrado");
        ResponseEntity<StorageGroupDTO> renamed = restTemplate.exchange(
                "/api/storage-groups/" + created.getId(), HttpMethod.PUT, new HttpEntity<>(rename), StorageGroupDTO.class);
        assertEquals(200, renamed.getStatusCode().value());
        assertNotNull(renamed.getBody());
        assertEquals("Grupo CRUD renombrado", renamed.getBody().getName());

        // Delete an empty group
        ResponseEntity<Void> deleted = restTemplate.exchange(
                "/api/storage-groups/" + created.getId(), HttpMethod.DELETE, null, Void.class);
        assertEquals(204, deleted.getStatusCode().value());
        ResponseEntity<Map> gone = restTemplate.getForEntity("/api/storage-groups/" + created.getId(), Map.class);
        assertEquals(404, gone.getStatusCode().value());
    }

    @Test
    void cannotDeleteGroupWithUnits() {
        StorageGroupDTO group = defaultGroup();
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/storage-groups/" + group.getId(), HttpMethod.DELETE, null, Map.class);
        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void creatingAUnitRequiresAGroupAndStatisticsCanBeFilteredByGroup() {
        StorageGroupDTO newGroup = createGroup("Grupo Estadísticas");

        // Missing group -> validation error
        StorageUnitRequest noGroup = unitRequest("G-NOGROUP", null);
        ResponseEntity<Map> invalid = restTemplate.postForEntity("/api/storages", noGroup, Map.class);
        assertEquals(400, invalid.getStatusCode().value());

        // Unit in the new group
        ResponseEntity<StorageUnit> created = restTemplate.postForEntity(
                "/api/storages", unitRequest("G-100", newGroup.getId()), StorageUnit.class);
        assertEquals(201, created.getStatusCode().value());
        assertNotNull(created.getBody());
        assertNotNull(created.getBody().getStorageGroup());
        assertEquals(newGroup.getId(), created.getBody().getStorageGroup().getId());

        // Dashboard restricted to the new group only sees that unit and no revenue
        ResponseEntity<DashboardStatsDTO> onlyNew = restTemplate.getForEntity(
                "/api/statistics/dashboard?groupIds=" + newGroup.getId(), DashboardStatsDTO.class);
        assertEquals(200, onlyNew.getStatusCode().value());
        assertNotNull(onlyNew.getBody());
        assertEquals(1, onlyNew.getBody().getTotalUnits());
        assertEquals(1, onlyNew.getBody().getAvailableUnits());
        assertEquals(0, onlyNew.getBody().getActiveRentals());
        assertEquals(0, BigDecimal.ZERO.compareTo(onlyNew.getBody().getTotalRevenueAllTime()));
        assertEquals(0, BigDecimal.ZERO.compareTo(onlyNew.getBody().getTotalExpensesAllTime()));
        assertEquals(1, onlyNew.getBody().getUnitsSummary().size());
        assertEquals(newGroup.getId(), onlyNew.getBody().getUnitsSummary().get(0).getStorageGroupId());

        // Dashboard restricted to the default group matches the seeded figures
        StorageGroupDTO defaultGroup = defaultGroup();
        ResponseEntity<DashboardStatsDTO> onlyDefault = restTemplate.getForEntity(
                "/api/statistics/dashboard?groupIds=" + defaultGroup.getId(), DashboardStatsDTO.class);
        assertNotNull(onlyDefault.getBody());
        assertEquals(9, onlyDefault.getBody().getTotalUnits());
        assertTrue(onlyDefault.getBody().getTotalRevenueAllTime().compareTo(BigDecimal.ZERO) > 0);
        assertTrue(onlyDefault.getBody().getTotalExpensesAllTime().compareTo(BigDecimal.ZERO) > 0,
                "General seeded expenses are attributed to the default group");

        // Both groups (comma separated) == no filter
        ResponseEntity<DashboardStatsDTO> both = restTemplate.getForEntity(
                "/api/statistics/dashboard?groupIds=" + defaultGroup.getId() + "," + newGroup.getId(), DashboardStatsDTO.class);
        ResponseEntity<DashboardStatsDTO> all = restTemplate.getForEntity("/api/statistics/dashboard", DashboardStatsDTO.class);
        assertNotNull(both.getBody());
        assertNotNull(all.getBody());
        assertEquals(all.getBody().getTotalUnits(), both.getBody().getTotalUnits());
        assertEquals(0, all.getBody().getTotalRevenueAllTime().compareTo(both.getBody().getTotalRevenueAllTime()));
        assertEquals(0, all.getBody().getTotalExpensesAllTime().compareTo(both.getBody().getTotalExpensesAllTime()));
        assertEquals(onlyDefault.getBody().getTotalUnits() + onlyNew.getBody().getTotalUnits(), all.getBody().getTotalUnits());

        // Trends accept the same filter
        ResponseEntity<List> monthly = restTemplate.getForEntity(
                "/api/statistics/monthly-trends?months=3&groupIds=" + newGroup.getId(), List.class);
        assertEquals(200, monthly.getStatusCode().value());
        assertNotNull(monthly.getBody());
        assertEquals(3, monthly.getBody().size());

        ResponseEntity<List> quarterly = restTemplate.getForEntity(
                "/api/statistics/quarterly-trends?quarters=2&groupIds=" + defaultGroup.getId(), List.class);
        assertEquals(200, quarterly.getStatusCode().value());

        ResponseEntity<List> annual = restTemplate.getForEntity(
                "/api/statistics/annual-trends?years=2&groupIds=" + defaultGroup.getId() + "&groupIds=" + newGroup.getId(), List.class);
        assertEquals(200, annual.getStatusCode().value());

        // Cleanup: the group can no longer be deleted while it has a unit
        ResponseEntity<Map> blocked = restTemplate.exchange(
                "/api/storage-groups/" + newGroup.getId(), HttpMethod.DELETE, null, Map.class);
        assertEquals(400, blocked.getStatusCode().value());
    }

    private static StorageUnitRequest unitRequest(String number, Long groupId) {
        StorageUnitRequest request = new StorageUnitRequest();
        request.setUnitNumber(number);
        request.setName("Trastero " + number);
        request.setStorageGroupId(groupId);
        request.setSizeSquareMeters(6.0);
        request.setBaseMonthlyRate(new BigDecimal("60.00"));
        request.setStatus(UnitStatus.AVAILABLE);
        return request;
    }
}
