package com.storagemanager.storage_management;

import com.storagemanager.storage_management.dto.AnnualRevenueDTO;
import com.storagemanager.storage_management.dto.DashboardStatsDTO;
import com.storagemanager.storage_management.dto.QuarterlyRevenueDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StatisticsControllerTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void testGetDashboardStats() {
        ResponseEntity<DashboardStatsDTO> response = restTemplate.getForEntity("/api/statistics/dashboard", DashboardStatsDTO.class);
        assertEquals(200, response.getStatusCodeValue());
        assertNotNull(response.getBody());
    }

    @Test
    void testGetMonthlyTrends() {
        ResponseEntity<List> response = restTemplate.getForEntity("/api/statistics/monthly-trends?months=3", List.class);
        assertEquals(200, response.getStatusCodeValue());
        assertNotNull(response.getBody());
    }

    @Test
    void testGetQuarterlyTrends() {
        ResponseEntity<List> response = restTemplate.getForEntity("/api/statistics/quarterly-trends?quarters=2", List.class);
        assertEquals(200, response.getStatusCodeValue());
        assertNotNull(response.getBody());
    }

    @Test
    void testGetAnnualTrends() {
        ResponseEntity<List> response = restTemplate.getForEntity("/api/statistics/annual-trends?years=2", List.class);
        assertEquals(200, response.getStatusCodeValue());
        assertNotNull(response.getBody());
    }

    @Test
    void testGetStatisticsByDateRange() {
        LocalDate startDate = LocalDate.of(2026, 1, 1);
        LocalDate endDate = LocalDate.of(2026, 6, 30);

        ResponseEntity<DashboardStatsDTO> response = restTemplate.getForEntity(
                "/api/statistics/range?startDate={startDate}&endDate={endDate}",
                DashboardStatsDTO.class,
                startDate,
                endDate);

        assertEquals(200, response.getStatusCodeValue());
        assertNotNull(response.getBody());
    }
}