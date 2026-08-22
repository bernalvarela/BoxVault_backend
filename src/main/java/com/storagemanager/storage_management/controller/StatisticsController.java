package com.storagemanager.storage_management.controller;

import com.storagemanager.storage_management.dto.DashboardStatsDTO;
import com.storagemanager.storage_management.dto.MonthlyRevenueDTO;
import com.storagemanager.storage_management.dto.UnitRevenueDTO;
import com.storagemanager.storage_management.service.StatisticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    @GetMapping("/unit-revenues")
    public ResponseEntity<List<UnitRevenueDTO>> getUnitRevenues() {
        return ResponseEntity.ok(statisticsService.getUnitRevenues());
    }
}
