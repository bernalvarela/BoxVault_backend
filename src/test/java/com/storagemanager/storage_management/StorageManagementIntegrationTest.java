package com.storagemanager.storage_management;

import com.storagemanager.storage_management.dto.DashboardStatsDTO;
import com.storagemanager.storage_management.dto.StorageUnitRequest;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.model.enums.UnitKind;
import com.storagemanager.storage_management.model.enums.UnitStatus;
import com.storagemanager.storage_management.model.enums.UnitType;
import com.storagemanager.storage_management.service.StatisticsService;
import com.storagemanager.storage_management.service.StorageUnitService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("h2")
class StorageManagementIntegrationTest {

    @Autowired
    private StorageUnitService storageUnitService;

    @Autowired
    private StatisticsService statisticsService;

    @Test
    void testStorageUnitsAndSeeder() {
        // Other tests may add units; the seed itself holds 9 trasteros and 2 apartments
        List<StorageUnit> storageUnits = storageUnitService.getUnits(UnitKind.STORAGE_UNIT, null);
        List<StorageUnit> apartments = storageUnitService.getUnits(UnitKind.APARTMENT, null);
        assertTrue(storageUnits.size() >= 9, "Should have the 9 preloaded storage units");
        assertTrue(apartments.size() >= 2, "Should have the 2 preloaded apartments (3D, 3E)");
        assertTrue(apartments.stream().anyMatch(u -> "3D".equals(u.getUnitNumber())));
        assertTrue(apartments.stream().anyMatch(u -> "3E".equals(u.getUnitNumber())));
    }

    @Test
    void testDashboardStatistics() {
        DashboardStatsDTO stats = statisticsService.getDashboardStats();
        assertNotNull(stats);
        assertTrue(stats.getTotalUnits() >= 11);
        assertTrue(stats.getStorageUnitCount() >= 9);
        assertTrue(stats.getApartmentCount() >= 2);
        assertTrue(stats.getOccupiedUnits() > 0, "Should have occupied units");
        assertTrue(stats.getOccupancyRate() > 0.0, "Occupancy rate should be > 0");
        assertNotNull(stats.getMonthlyPotentialRevenue());
        assertNotNull(stats.getCurrentMonthExpectedRevenue());
        assertNotNull(stats.getRecentMonthlyRevenue());
        assertFalse(stats.getRecentMonthlyRevenue().isEmpty());
    }
}
