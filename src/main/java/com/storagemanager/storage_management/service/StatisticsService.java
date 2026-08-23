package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.config.VatUtils;
import com.storagemanager.storage_management.dto.AnnualRevenueDTO;
import com.storagemanager.storage_management.dto.DashboardStatsDTO;
import com.storagemanager.storage_management.dto.MonthlyRevenueDTO;
import com.storagemanager.storage_management.dto.QuarterlyRevenueDTO;
import com.storagemanager.storage_management.dto.UnitOccupancyDTO;
import com.storagemanager.storage_management.dto.UnitRevenueDTO;
import com.storagemanager.storage_management.model.Payment;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.model.enums.PaymentStatus;
import com.storagemanager.storage_management.model.enums.RentalStatus;
import com.storagemanager.storage_management.model.enums.UnitStatus;
import com.storagemanager.storage_management.repository.ClientRepository;
import com.storagemanager.storage_management.repository.PaymentRepository;
import com.storagemanager.storage_management.repository.RentalAgreementRepository;
import com.storagemanager.storage_management.repository.StorageUnitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
public class StatisticsService {

    private final StorageUnitRepository storageUnitRepository;
    private final ClientRepository clientRepository;
    private final RentalAgreementRepository rentalAgreementRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;

    public DashboardStatsDTO getDashboardStats() {
        paymentService.checkAndUpdateOverduePayments();

        List<StorageUnit> allUnits = storageUnitRepository.findAll();
        long totalUnits = allUnits.size();
        long occupiedUnits = allUnits.stream().filter(u -> u.getStatus() == UnitStatus.OCCUPIED).count();
        long availableUnits = allUnits.stream().filter(u -> u.getStatus() == UnitStatus.AVAILABLE).count();
        long maintenanceUnits = allUnits.stream().filter(u -> u.getStatus() == UnitStatus.MAINTENANCE).count();
        long reservedUnits = allUnits.stream().filter(u -> u.getStatus() == UnitStatus.RESERVED).count();

        double occupancyRate = totalUnits > 0 ? ((double) occupiedUnits / totalUnits) * 100.0 : 0.0;
        occupancyRate = Math.round(occupancyRate * 10.0) / 10.0;

        long totalClients = clientRepository.count();
        long activeRentals = rentalAgreementRepository.countByStatus(RentalStatus.ACTIVE);

        BigDecimal potentialRevenue = allUnits.stream()
                .map(StorageUnit::getBaseMonthlyRate)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        LocalDate now = LocalDate.now();
        int currentYear = now.getYear();
        int currentMonth = now.getMonthValue();

        List<Payment> currentMonthPayments = paymentRepository.findByBillingPeriodYearAndBillingPeriodMonth(currentYear, currentMonth);

        BigDecimal currentMonthCollected = currentMonthPayments.stream()
                .filter(p -> p.getStatus() == PaymentStatus.PAID)
                .map(Payment::getAmountPaid)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal currentMonthPending = currentMonthPayments.stream()
                .filter(p -> p.getStatus() == PaymentStatus.PENDING || p.getStatus() == PaymentStatus.OVERDUE)
                .map(Payment::getAmountDue)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal currentMonthExpected = currentMonthPayments.stream()
                .map(Payment::getAmountDue)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (currentMonthExpected.compareTo(BigDecimal.ZERO) == 0) {
            // Si aún no se generaron facturas en el mes, calcular en base a contratos activos
            currentMonthExpected = rentalAgreementRepository.findAllActiveRentals().stream()
                    .map(RentalAgreement::getMonthlyRent)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        List<Payment> overduePayments = paymentRepository.findByStatus(PaymentStatus.OVERDUE);
        BigDecimal totalOverdueAmount = overduePayments.stream()
                .map(Payment::getAmountDue)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalRevenueAllTime = paymentRepository.sumTotalPaidRevenue();
        if (totalRevenueAllTime == null) totalRevenueAllTime = BigDecimal.ZERO;

        // 6 Meses de Histórico
        List<MonthlyRevenueDTO> recentMonthlyRevenue = getRecentMonthlyTrends(6);

        // Desglose de Trasteros
        List<UnitOccupancyDTO> unitsSummary = getUnitSummaryList(allUnits);

        return DashboardStatsDTO.builder()
                .totalUnits(totalUnits)
                .occupiedUnits(occupiedUnits)
                .availableUnits(availableUnits)
                .maintenanceUnits(maintenanceUnits)
                .reservedUnits(reservedUnits)
                .occupancyRate(occupancyRate)
                .totalClients(totalClients)
                .activeRentals(activeRentals)
                // Potencial mensual con IVA y desglose
                .monthlyPotentialRevenue(potentialRevenue)
                .monthlyPotentialRevenueWithoutVat(VatUtils.calculateBaseWithoutVat(potentialRevenue))
                .monthlyPotentialVatAmount(VatUtils.calculateVatAmount(potentialRevenue))
                // Esperado mes actual con IVA y desglose
                .currentMonthExpectedRevenue(currentMonthExpected)
                .currentMonthExpectedRevenueWithoutVat(VatUtils.calculateBaseWithoutVat(currentMonthExpected))
                .currentMonthExpectedVatAmount(VatUtils.calculateVatAmount(currentMonthExpected))
                // Cobrado mes actual con IVA y desglose
                .currentMonthCollectedRevenue(currentMonthCollected)
                .currentMonthCollectedRevenueWithoutVat(VatUtils.calculateBaseWithoutVat(currentMonthCollected))
                .currentMonthCollectedVatAmount(VatUtils.calculateVatAmount(currentMonthCollected))
                // Pendiente mes actual con IVA y desglose
                .currentMonthPendingRevenue(currentMonthPending)
                .currentMonthPendingRevenueWithoutVat(VatUtils.calculateBaseWithoutVat(currentMonthPending))
                .currentMonthPendingVatAmount(VatUtils.calculateVatAmount(currentMonthPending))
                // Vencidos con IVA y desglose
                .totalOverdueAmount(totalOverdueAmount)
                .totalOverdueWithoutVat(VatUtils.calculateBaseWithoutVat(totalOverdueAmount))
                .totalOverdueVatAmount(VatUtils.calculateVatAmount(totalOverdueAmount))
                .overduePaymentCount(overduePayments.size())
                // Histórico total con IVA y desglose
                .totalRevenueAllTime(totalRevenueAllTime)
                .totalRevenueAllTimeWithoutVat(VatUtils.calculateBaseWithoutVat(totalRevenueAllTime))
                .totalRevenueAllTimeVatAmount(VatUtils.calculateVatAmount(totalRevenueAllTime))
                .recentMonthlyRevenue(recentMonthlyRevenue)
                .unitsSummary(unitsSummary)
                .build();
    }

    public List<MonthlyRevenueDTO> getRecentMonthlyTrends(int numberOfMonths) {
        LocalDate current = LocalDate.now();
        List<MonthlyRevenueDTO> list = new ArrayList<>();
        DateTimeFormatter monthFormatter = DateTimeFormatter.ofPattern("MMM yyyy", new Locale("es", "ES"));

        for (int i = numberOfMonths - 1; i >= 0; i--) {
            LocalDate targetDate = current.minusMonths(i);
            int year = targetDate.getYear();
            int month = targetDate.getMonthValue();
            String label = targetDate.format(monthFormatter);

            List<Payment> payments = paymentRepository.findByBillingPeriodYearAndBillingPeriodMonth(year, month);

            BigDecimal expected = payments.stream()
                    .map(Payment::getAmountDue)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal collected = payments.stream()
                    .filter(p -> p.getStatus() == PaymentStatus.PAID)
                    .map(Payment::getAmountPaid)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal pending = payments.stream()
                    .filter(p -> p.getStatus() == PaymentStatus.PENDING || p.getStatus() == PaymentStatus.OVERDUE)
                    .map(Payment::getAmountDue)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            long paidCount = payments.stream().filter(p -> p.getStatus() == PaymentStatus.PAID).count();
            long pendingCount = payments.stream().filter(p -> p.getStatus() == PaymentStatus.PENDING).count();
            long overdueCount = payments.stream().filter(p -> p.getStatus() == PaymentStatus.OVERDUE).count();

            list.add(MonthlyRevenueDTO.builder()
                    .monthLabel(label)
                    .year(year)
                    .month(month)
                    .expectedRevenue(expected)
                    .expectedRevenueWithoutVat(VatUtils.calculateBaseWithoutVat(expected))
                    .expectedVatAmount(VatUtils.calculateVatAmount(expected))
                    .collectedRevenue(collected)
                    .collectedRevenueWithoutVat(VatUtils.calculateBaseWithoutVat(collected))
                    .collectedVatAmount(VatUtils.calculateVatAmount(collected))
                    .pendingRevenue(pending)
                    .pendingRevenueWithoutVat(VatUtils.calculateBaseWithoutVat(pending))
                    .pendingVatAmount(VatUtils.calculateVatAmount(pending))
                    .paidCount(paidCount)
                    .pendingCount(pendingCount)
                    .overdueCount(overdueCount)
                    .build());
        }

        return list;
    }

    /**
     * Get quarterly revenue trends for the specified number of quarters
     * @param numberOfQuarters number of quarters to go back from current quarter
     * @return list of quarterly revenue DTOs
     */
    public List<QuarterlyRevenueDTO> getQuarterlyTrends(int numberOfQuarters) {
        LocalDate current = LocalDate.now();
        List<QuarterlyRevenueDTO> list = new ArrayList<>();
        DateTimeFormatter quarterFormatter = DateTimeFormatter.ofPattern("QQQ yyyy", new Locale("es", "ES"));

        for (int i = numberOfQuarters - 1; i >= 0; i--) {
            // Calculate target date by going back i quarters (3 months each)
            LocalDate targetDate = current.minusMonths(3 * i);
            int year = targetDate.getYear();
            int month = targetDate.getMonthValue();
            int quarter = (month - 1) / 3 + 1; // Q1: Jan-Mar (1-3), Q2: Apr-Jun (4-6), etc.

            // Calculate start and end months for this quarter
            int startMonth = (quarter - 1) * 3 + 1;
            int endMonth = quarter * 3;

            String label = "Q" + quarter + " " + year;

            // Query payments for this quarter
            BigDecimal expectedRevenue = paymentRepository.sumRevenueForQuarter(year, startMonth, endMonth);
            if (expectedRevenue == null) expectedRevenue = BigDecimal.ZERO;

            BigDecimal pendingRevenue = paymentRepository.sumPendingRevenueForQuarter(year, startMonth, endMonth);
            if (pendingRevenue == null) pendingRevenue = BigDecimal.ZERO;

            // Calculate collected revenue (paid payments)
            List<Payment> quarterPayments = paymentRepository.findByBillingPeriodYearAndBillingPeriodMonthBetween(
                    year, startMonth, year, endMonth);

            BigDecimal collectedRevenue = quarterPayments.stream()
                    .filter(p -> p.getStatus() == PaymentStatus.PAID)
                    .map(Payment::getAmountPaid)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            long paidCount = quarterPayments.stream().filter(p -> p.getStatus() == PaymentStatus.PAID).count();
            long pendingCount = quarterPayments.stream().filter(p -> p.getStatus() == PaymentStatus.PENDING).count();
            long overdueCount = quarterPayments.stream().filter(p -> p.getStatus() == PaymentStatus.OVERDUE).count();

            list.add(QuarterlyRevenueDTO.builder()
                    .quarterLabel(label)
                    .year(year)
                    .quarter(quarter)
                    .expectedRevenue(expectedRevenue)
                    .expectedRevenueWithoutVat(VatUtils.calculateBaseWithoutVat(expectedRevenue))
                    .expectedVatAmount(VatUtils.calculateVatAmount(expectedRevenue))
                    .collectedRevenue(collectedRevenue)
                    .collectedRevenueWithoutVat(VatUtils.calculateBaseWithoutVat(collectedRevenue))
                    .collectedVatAmount(VatUtils.calculateVatAmount(collectedRevenue))
                    .pendingRevenue(pendingRevenue)
                    .pendingRevenueWithoutVat(VatUtils.calculateBaseWithoutVat(pendingRevenue))
                    .pendingVatAmount(VatUtils.calculateVatAmount(pendingRevenue))
                    .paidCount(paidCount)
                    .pendingCount(pendingCount)
                    .overdueCount(overdueCount)
                    .build());
        }

        return list;
    }

    /**
     * Get annual revenue trends for the specified number of years
     * @param numberOfYears number of years to go back from current year
     * @return list of annual revenue DTOs
     */
    public List<AnnualRevenueDTO> getAnnualTrends(int numberOfYears) {
        LocalDate current = LocalDate.now();
        List<AnnualRevenueDTO> list = new ArrayList<>();

        for (int i = numberOfYears - 1; i >= 0; i--) {
            int year = current.getYear() - i;
            String label = String.valueOf(year);

            // Query payments for this year
            BigDecimal expectedRevenue = paymentRepository.sumRevenueForYear(year);
            if (expectedRevenue == null) expectedRevenue = BigDecimal.ZERO;

            BigDecimal pendingRevenue = paymentRepository.sumPendingRevenueForYear(year);
            if (pendingRevenue == null) pendingRevenue = BigDecimal.ZERO;

            // Calculate collected revenue (paid payments)
            List<Payment> yearPayments = paymentRepository.findByBillingPeriodYear(year);

            BigDecimal collectedRevenue = yearPayments.stream()
                    .filter(p -> p.getStatus() == PaymentStatus.PAID)
                    .map(Payment::getAmountPaid)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            long paidCount = yearPayments.stream().filter(p -> p.getStatus() == PaymentStatus.PAID).count();
            long pendingCount = yearPayments.stream().filter(p -> p.getStatus() == PaymentStatus.PENDING).count();
            long overdueCount = yearPayments.stream().filter(p -> p.getStatus() == PaymentStatus.OVERDUE).count();

            list.add(AnnualRevenueDTO.builder()
                    .yearLabel(label)
                    .year(year)
                    .expectedRevenue(expectedRevenue)
                    .expectedRevenueWithoutVat(VatUtils.calculateBaseWithoutVat(expectedRevenue))
                    .expectedVatAmount(VatUtils.calculateVatAmount(expectedRevenue))
                    .collectedRevenue(collectedRevenue)
                    .collectedRevenueWithoutVat(VatUtils.calculateBaseWithoutVat(collectedRevenue))
                    .collectedVatAmount(VatUtils.calculateVatAmount(collectedRevenue))
                    .pendingRevenue(pendingRevenue)
                    .pendingRevenueWithoutVat(VatUtils.calculateBaseWithoutVat(pendingRevenue))
                    .pendingVatAmount(VatUtils.calculateVatAmount(pendingRevenue))
                    .paidCount(paidCount)
                    .pendingCount(pendingCount)
                    .overdueCount(overdueCount)
                    .build());
        }

        return list;
    }

    /**
     * Get statistics for a custom date range
     * @param startDate start date (inclusive)
     * @param endDate end date (inclusive)
     * @return dashboard stats for the specified date range
     */
    public DashboardStatsDTO getStatisticsByDateRange(LocalDate startDate, LocalDate endDate) {
        // Validate date range
        if (startDate == null || endDate == null || startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("Invalid date range");
        }

        // For now, we'll reuse the dashboard stats logic but filter by date range
        // In a more sophisticated implementation, we might want to create a separate DTO
        // or modify the repository to support date range queries

        List<StorageUnit> allUnits = storageUnitRepository.findAll();
        long totalUnits = allUnits.size();
        long occupiedUnits = allUnits.stream().filter(u -> u.getStatus() == UnitStatus.OCCUPIED).count();
        long availableUnits = allUnits.stream().filter(u -> u.getStatus() == UnitStatus.AVAILABLE).count();
        long maintenanceUnits = allUnits.stream().filter(u -> u.getStatus() == UnitStatus.MAINTENANCE).count();
        long reservedUnits = allUnits.stream().filter(u -> u.getStatus() == UnitStatus.RESERVED).count();

        double occupancyRate = totalUnits > 0 ? ((double) occupiedUnits / totalUnits) * 100.0 : 0.0;
        occupancyRate = Math.round(occupancyRate * 10.0) / 10.0;

        long totalClients = clientRepository.count();
        long activeRentals = rentalAgreementRepository.countByStatus(RentalStatus.ACTIVE);

        BigDecimal potentialRevenue = allUnits.stream()
                .map(StorageUnit::getBaseMonthlyRate)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Get payments within the date range
        List<Payment> rangePayments = paymentRepository.findByDueDateBetween(startDate, endDate);

        BigDecimal collectedRevenue = rangePayments.stream()
                .filter(p -> p.getStatus() == PaymentStatus.PAID)
                .map(Payment::getAmountPaid)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal pendingRevenue = rangePayments.stream()
                .filter(p -> p.getStatus() == PaymentStatus.PENDING || p.getStatus() == PaymentStatus.OVERDUE)
                .map(Payment::getAmountDue)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal expectedRevenue = rangePayments.stream()
                .map(Payment::getAmountDue)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Payment> overduePayments = paymentRepository.findByStatus(PaymentStatus.OVERDUE);
        BigDecimal totalOverdueAmount = overduePayments.stream()
                .map(Payment::getAmountDue)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalRevenueAllTime = paymentRepository.sumTotalPaidRevenue();
        if (totalRevenueAllTime == null) totalRevenueAllTime = BigDecimal.ZERO;

        // For date range stats, we don't have monthly trends, so we'll use an empty list
        List<MonthlyRevenueDTO> recentMonthlyRevenue = Collections.emptyList();

        // Desglose de Trasteros (using all units for now, could be filtered by date range if needed)
        List<UnitOccupancyDTO> unitsSummary = getUnitSummaryList(allUnits);

        return DashboardStatsDTO.builder()
                .totalUnits(totalUnits)
                .occupiedUnits(occupiedUnits)
                .availableUnits(availableUnits)
                .maintenanceUnits(maintenanceUnits)
                .reservedUnits(reservedUnits)
                .occupancyRate(occupancyRate)
                .totalClients(totalClients)
                .activeRentals(activeRentals)
                // Potencial mensual con IVA y desglose
                .monthlyPotentialRevenue(potentialRevenue)
                .monthlyPotentialRevenueWithoutVat(VatUtils.calculateBaseWithoutVat(potentialRevenue))
                .monthlyPotentialVatAmount(VatUtils.calculateVatAmount(potentialRevenue))
                // Expected revenue for the date range
                .currentMonthExpectedRevenue(expectedRevenue)
                .currentMonthExpectedRevenueWithoutVat(VatUtils.calculateBaseWithoutVat(expectedRevenue))
                .currentMonthExpectedVatAmount(VatUtils.calculateVatAmount(expectedRevenue))
                // Collected revenue for the date range
                .currentMonthCollectedRevenue(collectedRevenue)
                .currentMonthCollectedRevenueWithoutVat(VatUtils.calculateBaseWithoutVat(collectedRevenue))
                .currentMonthCollectedVatAmount(VatUtils.calculateVatAmount(collectedRevenue))
                // Pending revenue for the date range
                .currentMonthPendingRevenue(pendingRevenue)
                .currentMonthPendingRevenueWithoutVat(VatUtils.calculateBaseWithoutVat(pendingRevenue))
                .currentMonthPendingVatAmount(VatUtils.calculateVatAmount(pendingRevenue))
                // Overdue amount (not date-range specific, kept as-is for consistency)
                .totalOverdueAmount(totalOverdueAmount)
                .totalOverdueWithoutVat(VatUtils.calculateBaseWithoutVat(totalOverdueAmount))
                .totalOverdueVatAmount(VatUtils.calculateVatAmount(totalOverdueAmount))
                .overduePaymentCount(overduePayments.size())
                // Total revenue all-time (not date-range specific, kept as-is for consistency)
                .totalRevenueAllTime(totalRevenueAllTime)
                .totalRevenueAllTimeWithoutVat(VatUtils.calculateBaseWithoutVat(totalRevenueAllTime))
                .totalRevenueAllTimeVatAmount(VatUtils.calculateVatAmount(totalRevenueAllTime))
                .recentMonthlyRevenue(recentMonthlyRevenue)
                .unitsSummary(unitsSummary)
                .build();
    }

    private List<UnitOccupancyDTO> getUnitSummaryList(List<StorageUnit> allUnits) {
        List<UnitOccupancyDTO> result = new ArrayList<>();
        List<RentalAgreement> activeAgreements = rentalAgreementRepository.findByStatus(RentalStatus.ACTIVE);
        Map<Long, RentalAgreement> unitToAgreement = new HashMap<>();
        for (RentalAgreement ra : activeAgreements) {
            unitToAgreement.put(ra.getStorageUnit().getId(), ra);
        }

        List<Object[]> revenueByUnitRaw = paymentRepository.sumRevenueByStorageUnit();
        Map<Long, BigDecimal> unitRevenues = new HashMap<>();
        for (Object[] row : revenueByUnitRaw) {
            Long uId = (Long) row[0];
            BigDecimal sum = (BigDecimal) row[2];
            unitRevenues.put(uId, sum);
        }

        for (StorageUnit unit : allUnits) {
            RentalAgreement active = unitToAgreement.get(unit.getId());
            BigDecimal rev = unitRevenues.getOrDefault(unit.getId(), BigDecimal.ZERO);
            BigDecimal monthlyRent = active != null ? active.getMonthlyRent() : null;

            result.add(UnitOccupancyDTO.builder()
                    .id(unit.getId())
                    .unitNumber(unit.getUnitNumber())
                    .name(unit.getName())
                    .sizeSquareMeters(unit.getSizeSquareMeters())
                    .status(unit.getStatus())
                    .location(unit.getLocation())
                    .currentClientName(active != null ? active.getClient().getFullName() : null)
                    .currentAgreementNumber(active != null ? active.getAgreementNumber() : null)
                    // Tarifa Base (con IVA y desglose)
                    .baseMonthlyRate(unit.getBaseMonthlyRate())
                    .baseMonthlyRateWithoutVat(VatUtils.calculateBaseWithoutVat(unit.getBaseMonthlyRate()))
                    .baseMonthlyRateVatAmount(VatUtils.calculateVatAmount(unit.getBaseMonthlyRate()))
                    // Alquiler Actual
                    .actualMonthlyRent(monthlyRent)
                    .actualMonthlyRentWithoutVat(monthlyRent != null ? VatUtils.calculateBaseWithoutVat(monthlyRent) : null)
                    .actualMonthlyRentVatAmount(monthlyRent != null ? VatUtils.calculateVatAmount(monthlyRent) : null)
                    // Ingresos acumulados
                    .totalRevenueGenerated(rev)
                    .totalRevenueGeneratedWithoutVat(VatUtils.calculateBaseWithoutVat(rev))
                    .totalRevenueGeneratedVatAmount(VatUtils.calculateVatAmount(rev))
                    .build());
        }

        return result;
    }

    public List<UnitRevenueDTO> getUnitRevenues() {
        List<Object[]> rows = paymentRepository.sumRevenueByStorageUnit();
        List<UnitRevenueDTO> list = new ArrayList<>();
        for (Object[] r : rows) {
            list.add(new UnitRevenueDTO((Long) r[0], (String) r[1], "", (BigDecimal) r[2]));
        }
        return list;
    }
}
