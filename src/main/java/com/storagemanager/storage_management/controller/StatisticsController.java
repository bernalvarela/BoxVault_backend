package com.storagemanager.storage_management.controller;

import com.storagemanager.storage_management.dto.AnnualRevenueDTO;
import com.storagemanager.storage_management.dto.DashboardStatsDTO;
import com.storagemanager.storage_management.dto.HistoryRangeDTO;
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

/**
 * Statistics endpoints. Every endpoint accepts an optional {@code rootIds} query
 * parameter (repeated, e.g. {@code ?rootIds=1&rootIds=2}, or comma separated,
 * {@code ?rootIds=1,2}) restricting the figures to those root units (locales or stand-alone flats). Omitting
 * it, or passing an empty value, returns the figures for everything.
 */
@RestController
@RequestMapping("/api/statistics")
@RequiredArgsConstructor
public class StatisticsController {

    private final StatisticsService statisticsService;

    @GetMapping("/dashboard")
    public ResponseEntity<DashboardStatsDTO> getDashboardStats(
            @RequestParam(required = false) List<Long> rootIds) {
        return ResponseEntity.ok(statisticsService.getDashboardStats(rootIds));
    }

    @GetMapping("/monthly-trends")
    public ResponseEntity<List<MonthlyRevenueDTO>> getMonthlyTrends(
            @RequestParam(defaultValue = "6") int months,
            @RequestParam(required = false) List<Long> rootIds) {
        return ResponseEntity.ok(statisticsService.getRecentMonthlyTrends(months, rootIds));
    }

    @GetMapping("/quarterly-trends")
    public ResponseEntity<List<QuarterlyRevenueDTO>> getQuarterlyTrends(
            @RequestParam(defaultValue = "4") int quarters,
            @RequestParam(required = false) List<Long> rootIds) {
        return ResponseEntity.ok(statisticsService.getQuarterlyTrends(quarters, rootIds));
    }

    @GetMapping("/annual-trends")
    public ResponseEntity<List<AnnualRevenueDTO>> getAnnualTrends(
            @RequestParam(defaultValue = "3") int years,
            @RequestParam(required = false) List<Long> rootIds) {
        return ResponseEntity.ok(statisticsService.getAnnualTrends(years, rootIds));
    }

    @GetMapping("/range")
    public ResponseEntity<DashboardStatsDTO> getStatisticsByDateRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) List<Long> rootIds) {
        return ResponseEntity.ok(statisticsService.getStatisticsByDateRange(startDate, endDate, rootIds));
    }

    /** First and last activity date of the groups, for the "whole history" statistics range. */
    @GetMapping("/history-range")
    public ResponseEntity<HistoryRangeDTO> getHistoryRange(
            @RequestParam(required = false) List<Long> rootIds) {
        return ResponseEntity.ok(statisticsService.getHistoryRange(rootIds));
    }

    @GetMapping("/unit-revenues")
    public ResponseEntity<List<UnitRevenueDTO>> getUnitRevenues(
            @RequestParam(required = false) List<Long> rootIds) {
        return ResponseEntity.ok(statisticsService.getUnitRevenues(rootIds));
    }
}
