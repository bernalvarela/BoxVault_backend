package com.storagemanager.storage_management.controller;

import com.storagemanager.storage_management.dto.AnnualRevenueDTO;
import com.storagemanager.storage_management.dto.DashboardStatsDTO;
import com.storagemanager.storage_management.dto.MonthlyRevenueDTO;
import com.storagemanager.storage_management.dto.QuarterlyRevenueDTO;
import com.storagemanager.storage_management.dto.UnitRevenueDTO;
import com.storagemanager.storage_management.service.StatisticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/statistics")
@RequiredArgsConstructor

public class StatisticsController {

    private final StatisticsService statisticsService;

    @GetMapping("/dashboard")
    public ResponseEntity<DashboardStatsDTO> getDashboardStats() {
        return ResponseEntity.ok(statisticsService.getDashboardStats());
    }

    @GetMapping("/monthly-trends")
    public ResponseEntity<List<MonthlyRevenueDTO>> getMonthlyTrends(@RequestParam(defaultValue = "6") int months) {
        return ResponseEntity.ok(statisticsService.getRecentMonthlyTrends(months));
    }

    @GetMapping("/quarterly-trends")
    public ResponseEntity<List<QuarterlyRevenueDTO>> getQuarterlyTrends(@RequestParam(defaultValue = "4") int quarters) {
        return ResponseEntity.ok(statisticsService.getQuarterlyTrends(quarters));
    }

    @GetMapping("/annual-trends")
    public ResponseEntity<List<AnnualRevenueDTO>> getAnnualTrends(@RequestParam(defaultValue = "3") int years) {
        return ResponseEntity.ok(statisticsService.getAnnualTrends(years));
    }

    @GetMapping("/range")
    public ResponseEntity<DashboardStatsDTO> getStatisticsByDateRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(statisticsService.getStatisticsByDateRange(startDate, endDate));
    }

    @GetMapping("/unit-revenues")
    public ResponseEntity<List<UnitRevenueDTO>> getUnitRevenues() {
        return ResponseEntity.ok(statisticsService.getUnitRevenues());
    }
}
