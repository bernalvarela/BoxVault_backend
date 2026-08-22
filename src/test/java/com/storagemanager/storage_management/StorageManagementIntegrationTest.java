package com.storagemanager.storage_management;

import com.storagemanager.storage_management.dto.DashboardStatsDTO;
import com.storagemanager.storage_management.dto.StorageUnitRequest;
import com.storagemanager.storage_management.model.StorageUnit;
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
        List<StorageUnit> units = storageUnitService.getAllUnits();
        assertEquals(9, units.size(), "Should have exactly 9 preloaded storage units");
    }

    @Test
    void testDashboardStatistics() {
        DashboardStatsDTO stats = statisticsService.getDashboardStats();
        assertNotNull(stats);
        assertEquals(9, stats.getTotalUnits());
        assertTrue(stats.getOccupiedUnits() > 0, "Should have occupied units");
        assertTrue(stats.getOccupancyRate() > 0.0, "Occupancy rate should be > 0");
        assertNotNull(stats.getMonthlyPotentialRevenue());
        assertNotNull(stats.getCurrentMonthExpectedRevenue());
        assertNotNull(stats.getRecentMonthlyRevenue());
        assertFalse(stats.getRecentMonthlyRevenue().isEmpty());
    }
}
