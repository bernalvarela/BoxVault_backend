package com.storagemanager.storage_management;

import com.storagemanager.storage_management.dto.MonthlyChargeDTO;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.model.enums.RentalStatus;
import com.storagemanager.storage_management.repository.PaymentRepository;
import com.storagemanager.storage_management.repository.RentalAgreementRepository;
import com.storagemanager.storage_management.security.UnitScope;
import com.storagemanager.storage_management.service.BillingService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Las mensualidades no se guardan: salen de los contratos. Lo que se comprueba
 * aquí es qué meses debe cada uno, que es de donde salían los cargos fantasma.
 */
class BillingChargesTest {

    private static final StorageUnit TRASTERO = StorageUnit.builder().id(1L).unitNumber("2").build();

    private static RentalAgreement rental(long id, String start, String end, RentalStatus status) {
        return RentalAgreement.builder()
                .id(id)
                .agreementNumber("RNT-" + id)
                .storageUnit(TRASTERO)
                .startDate(LocalDate.parse(start))
                .endDate(end == null ? null : LocalDate.parse(end))
                .status(status)
                .billingDayOfMonth(1)
                .monthlyRent(new BigDecimal("50.00"))
                .build();
    }

    private static BillingService serviceWith(RentalAgreement... rentals) {
        RentalAgreementRepository rentalRepository = mock(RentalAgreementRepository.class);
        when(rentalRepository.findAll()).thenReturn(List.of(rentals));
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        when(paymentRepository.findAll()).thenReturn(List.of());
        UnitScope unitScope = mock(UnitScope.class);
        // Sin ámbito: el usuario ve todas las unidades.
        when(unitScope.filterByUnit(anyList(), any())).thenAnswer(call -> call.getArgument(0));
        return new BillingService(rentalRepository, paymentRepository, unitScope);
    }

    /** Los meses que debe un contrato, como "2025-01". */
    private static List<String> monthsOf(List<MonthlyChargeDTO> charges, long rentalId) {
        return charges.stream()
                .filter(c -> c.getRentalAgreementId() == rentalId)
                .map(c -> c.getBillingPeriodYear() + "-" + String.format("%02d", c.getBillingPeriodMonth()))
                .sorted()
                .toList();
    }

    @Test
    void aContractEndingTheFirstOfTheMonthDoesNotOweThatMonth() {
        // Como se anotaba antes: el contrato termina el día en que entra el siguiente
        RentalAgreement saliente = rental(1, "2025-01-01", "2025-04-01", RentalStatus.TERMINATED);
        RentalAgreement entrante = rental(2, "2025-04-01", null, RentalStatus.ACTIVE);

        List<MonthlyChargeDTO> charges = serviceWith(saliente, entrante)
                .charges(YearMonth.of(2025, 1), YearMonth.of(2025, 5));

        // Abril es del que entra, no del que se va: ya no hay cargo fantasma
        assertEquals(List.of("2025-01", "2025-02", "2025-03"), monthsOf(charges, 1));
        assertEquals(List.of("2025-04", "2025-05"), monthsOf(charges, 2));
        assertEquals(1, charges.stream().filter(c -> c.getBillingPeriodMonth() == 4).count(),
                "abril se cobra una sola vez");
    }

    @Test
    void aContractEndingMidMonthStillOwesIt() {
        // La renta venció el día 1 con el inquilino dentro: ese mes se debe entero
        RentalAgreement rental = rental(3, "2025-01-01", "2025-04-15", RentalStatus.TERMINATED);

        List<MonthlyChargeDTO> charges = serviceWith(rental)
                .charges(YearMonth.of(2025, 1), YearMonth.of(2025, 5));

        assertEquals(List.of("2025-01", "2025-02", "2025-03", "2025-04"), monthsOf(charges, 3));
    }

    @Test
    void theEndingMonthIsOwedWhenTheRentFallsDueBeforeLeaving() {
        // Día de cobro el 10 y se va el día 20: le dio tiempo a vencer
        RentalAgreement rental = rental(4, "2025-01-01", "2025-03-20", RentalStatus.TERMINATED);
        rental.setBillingDayOfMonth(10);

        List<MonthlyChargeDTO> charges = serviceWith(rental)
                .charges(YearMonth.of(2025, 1), YearMonth.of(2025, 4));

        assertEquals(List.of("2025-01", "2025-02", "2025-03"), monthsOf(charges, 4));

        // Y si se va el día 5, antes de que venza, ese mes no lo debe
        RentalAgreement antes = rental(5, "2025-01-01", "2025-03-05", RentalStatus.TERMINATED);
        antes.setBillingDayOfMonth(10);
        assertEquals(List.of("2025-01", "2025-02"),
                monthsOf(serviceWith(antes).charges(YearMonth.of(2025, 1), YearMonth.of(2025, 4)), 5));
    }
}
