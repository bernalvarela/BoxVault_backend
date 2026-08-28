package com.storagemanager.storage_management.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardStatsDTO {
    // Storage metrics (totalUnits = storageUnitCount + apartmentCount)
    private long totalUnits;
    private long storageUnitCount;
    private long apartmentCount;
    private long occupiedUnits;
    private long availableUnits;
    private long maintenanceUnits;
    private long reservedUnits;
    private double occupancyRate; // e.g. 66.7 %

    // Client & Rental metrics
    private long totalClients;
    private long activeClients; // Clientes con al menos un alquiler activo
    private long activeRentals;

    // Financial Overview (Gross with 21% VAT)
    private BigDecimal monthlyPotentialRevenue; // 100% capacity (Con IVA)
    private BigDecimal monthlyPotentialRevenueWithoutVat; // Base sin IVA
    private BigDecimal monthlyPotentialVatAmount; // Cuota IVA 21%

    private BigDecimal currentMonthExpectedRevenue; // Con IVA
    private BigDecimal currentMonthExpectedRevenueWithoutVat; // Base sin IVA
    private BigDecimal currentMonthExpectedVatAmount; // Cuota IVA 21%

    private BigDecimal currentMonthCollectedRevenue; // Con IVA
    private BigDecimal currentMonthCollectedRevenueWithoutVat; // Base sin IVA
    private BigDecimal currentMonthCollectedVatAmount; // Cuota IVA 21%

    private BigDecimal currentMonthPendingRevenue; // Con IVA
    private BigDecimal currentMonthPendingRevenueWithoutVat; // Base sin IVA
    private BigDecimal currentMonthPendingVatAmount; // Cuota IVA 21%

    private BigDecimal totalOverdueAmount; // Con IVA
    private BigDecimal totalOverdueWithoutVat; // Base sin IVA
    private BigDecimal totalOverdueVatAmount; // Cuota IVA 21%
    private long overduePaymentCount;

    private BigDecimal totalRevenueAllTime; // Con IVA
    private BigDecimal totalRevenueAllTimeWithoutVat; // Base sin IVA
    private BigDecimal totalRevenueAllTimeVatAmount; // Cuota IVA 21%

    // Gastos (importe pagado, sin desglose de IVA) y resultado neto = cobrado - gastos.
    // En /dashboard "currentMonth*" se refiere al mes en curso; en /range, al rango pedido.
    private BigDecimal currentMonthExpenses;
    private long currentMonthExpenseCount;
    private BigDecimal currentMonthNetResult;

    private BigDecimal totalExpensesAllTime;
    private BigDecimal netResultAllTime;

    // Breakdown lists
    private List<MonthlyRevenueDTO> recentMonthlyRevenue;
    private List<UnitOccupancyDTO> unitsSummary;
    private List<ExpenseCategorySummaryDTO> expensesByCategory; // Histórico total en /dashboard; rango en /range
}
