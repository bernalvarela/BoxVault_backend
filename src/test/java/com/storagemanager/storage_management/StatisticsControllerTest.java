package com.storagemanager.storage_management;

import com.storagemanager.storage_management.dto.AnnualRevenueDTO;
import com.storagemanager.storage_management.dto.DashboardStatsDTO;
import com.storagemanager.storage_management.dto.QuarterlyRevenueDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class StatisticsControllerTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void testGetDashboardStats() {
        ResponseEntity<DashboardStatsDTO> response = restTemplate.getForEntity("/api/statistics/dashboard", DashboardStatsDTO.class);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
    }

    @Test
    void testGetMonthlyTrends() {
        ResponseEntity<List> response = restTemplate.getForEntity("/api/statistics/monthly-trends?months=3", List.class);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
    }

    @Test
    void testGetQuarterlyTrends() {
        ResponseEntity<List> response = restTemplate.getForEntity("/api/statistics/quarterly-trends?quarters=2", List.class);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
    }

    @Test
    void testGetAnnualTrends() {
        ResponseEntity<List> response = restTemplate.getForEntity("/api/statistics/annual-trends?years=2", List.class);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
    }

    @Test
    void testTrendsWithZeroPeriodsReturnCurrentPeriod() {
        ResponseEntity<List> annual = restTemplate.getForEntity("/api/statistics/annual-trends?years=0", List.class);
        assertEquals(200, annual.getStatusCode().value());
        assertNotNull(annual.getBody());
        assertEquals(1, annual.getBody().size());

        ResponseEntity<List> quarterly = restTemplate.getForEntity("/api/statistics/quarterly-trends?quarters=0", List.class);
        assertEquals(200, quarterly.getStatusCode().value());
        assertNotNull(quarterly.getBody());
        assertEquals(1, quarterly.getBody().size());

        ResponseEntity<List> monthly = restTemplate.getForEntity("/api/statistics/monthly-trends?months=0", List.class);
        assertEquals(200, monthly.getStatusCode().value());
        assertNotNull(monthly.getBody());
        assertEquals(1, monthly.getBody().size());
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

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
    }
}