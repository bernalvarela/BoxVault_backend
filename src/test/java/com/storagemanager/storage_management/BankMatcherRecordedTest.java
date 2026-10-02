package com.storagemanager.storage_management;

import com.storagemanager.storage_management.model.BankImportLine;
import com.storagemanager.storage_management.model.BankImportProfile;
import com.storagemanager.storage_management.model.Expense;
import com.storagemanager.storage_management.model.Payment;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.model.enums.BankLineAction;
import com.storagemanager.storage_management.model.enums.BankLineStatus;
import com.storagemanager.storage_management.model.enums.BankProfileContext;
import com.storagemanager.storage_management.model.enums.ExpenseCategory;
import com.storagemanager.storage_management.model.enums.PaymentStatus;
import com.storagemanager.storage_management.repository.BankMatchRuleRepository;
import com.storagemanager.storage_management.repository.ExpenseRepository;
import com.storagemanager.storage_management.repository.OwnershipRepository;
import com.storagemanager.storage_management.repository.PaymentRepository;
import com.storagemanager.storage_management.repository.RentalAgreementRepository;
import com.storagemanager.storage_management.service.bank.BankMatcher;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Que un movimiento ya apuntado a mano no se apunte otra vez: los pagos de
 * varios meses de golpe y los gastos (el IBI) que se metieron antes de importar.
 * Sin base de datos: los repositorios devuelven listas hechas a mano.
 */
class BankMatcherRecordedTest {

    private final List<RentalAgreement> rentals = new ArrayList<>();
    private final List<Payment> payments = new ArrayList<>();
    private final List<Expense> expenses = new ArrayList<>();
    private long nextId = 100;

    private BankMatcher matcher() {
        RentalAgreementRepository rentalRepo = mock(RentalAgreementRepository.class);
        PaymentRepository paymentRepo = mock(PaymentRepository.class);
        BankMatchRuleRepository ruleRepo = mock(BankMatchRuleRepository.class);
        OwnershipRepository ownershipRepo = mock(OwnershipRepository.class);
        ExpenseRepository expenseRepo = mock(ExpenseRepository.class);
        when(rentalRepo.findAll()).thenReturn(rentals);
        when(paymentRepo.findAll()).thenReturn(payments);
        when(ruleRepo.findAll()).thenReturn(List.of());
        when(ownershipRepo.findAll()).thenReturn(List.of());
        when(expenseRepo.findAll()).thenReturn(expenses);
        return new BankMatcher(rentalRepo, paymentRepo, ruleRepo, ownershipRepo, expenseRepo);
    }

    private static BankImportProfile trasteros() {
        return BankImportProfile.builder().id(1L).name("BBVA trasteros").context(BankProfileContext.PROPIETARIOS).build();
    }

    private RentalAgreement trastero(String number, String rent, LocalDate start) {
        StorageUnit unit = StorageUnit.builder().id(nextId++).unitNumber(number).name("Trastero " + number).build();
        RentalAgreement rental = RentalAgreement.builder()
                .id(nextId++).agreementNumber("RNT-" + number).storageUnit(unit)
                .startDate(start).monthlyRent(new BigDecimal(rent)).build();
        rentals.add(rental);
        return rental;
    }

    private Payment paid(RentalAgreement rental, YearMonth month, LocalDate paidOn) {
        Payment p = Payment.builder()
                .id(nextId++).rentalAgreement(rental)
                .billingPeriodYear(month.getYear()).billingPeriodMonth(month.getMonthValue())
                .amountDue(rental.getMonthlyCharge()).amountPaid(rental.getMonthlyCharge())
                .paymentDate(paidOn).status(PaymentStatus.PAID).build();
        payments.add(p);
        return p;
    }

    private static BankImportLine line(LocalDate date, String concept, String amount) {
        return BankImportLine.builder().date(date).concept(concept).amount(new BigDecimal(amount)).build();
    }

    /** Lo que hace la importación: primera pasada por quien paga y, si no, la propuesta. */
    private static void match(BankMatcher matcher, BankMatcher.Context ctx, BankImportLine line, BankImportProfile profile) {
        if (!matcher.markRecordedByPayer(line, profile, ctx)) matcher.propose(line, profile, ctx);
    }

    @Test
    void namedMonthsAlreadyPaidAreRecordedEvenIfEnteredOnAnotherDay() {
        RentalAgreement rental = trastero("6", "55.00", LocalDate.of(2024, 1, 1));
        Payment july = paid(rental, YearMonth.of(2026, 7), LocalDate.of(2026, 6, 2));
        paid(rental, YearMonth.of(2026, 8), LocalDate.of(2026, 6, 2));
        BankMatcher matcher = matcher();

        BankImportLine line = line(LocalDate.of(2026, 7, 17),
                "TRANSFERENCIAS Mes de julio y agosto trastero 6 Av Pasaxe", "110.00");
        match(matcher, matcher.load(), line, trasteros());

        assertTrue(line.getAlreadyRecorded());
        assertEquals(BankLineStatus.DISCARDED, line.getStatus());
        assertEquals(july.getId(), line.getPaymentId());
        assertEquals(2, line.getPeriodCount());
        assertTrue(line.getReason().contains("julio y agosto de 2026"), line.getReason());
    }

    @Test
    void severalMonthsRecordedTheSameDayAreOnePayment() {
        RentalAgreement rental = trastero("4", "50.00", LocalDate.of(2026, 7, 1));
        LocalDate recordedOn = LocalDate.of(2026, 7, 20);
        paid(rental, YearMonth.of(2026, 7), recordedOn);
        paid(rental, YearMonth.of(2026, 8), recordedOn);
        paid(rental, YearMonth.of(2026, 9), recordedOn);
        BankMatcher matcher = matcher();

        BankImportLine line = line(LocalDate.of(2026, 7, 6), "TRANSFERENCIAS pago de trastero número 4", "150.00");
        assertTrue(matcher.markRecordedByPayer(line, trasteros(), matcher.load()));
        assertTrue(line.getAlreadyRecorded());
        assertEquals(3, line.getPeriodCount());
        assertEquals(7, line.getPeriodMonth());
    }

    @Test
    void aPrepaymentRecordedMonthByMonthIsRecognised() {
        RentalAgreement rental = trastero("4", "50.00", LocalDate.of(2026, 7, 1));
        Payment july = paid(rental, YearMonth.of(2026, 7), LocalDate.of(2026, 7, 1));
        paid(rental, YearMonth.of(2026, 8), LocalDate.of(2026, 8, 1));
        paid(rental, YearMonth.of(2026, 9), LocalDate.of(2026, 9, 1));
        BankMatcher matcher = matcher();

        BankImportLine line = line(LocalDate.of(2026, 7, 6), "TRANSFERENCIAS pago de trastero número 4", "150.00");
        match(matcher, matcher.load(), line, trasteros());

        assertTrue(line.getAlreadyRecorded(), line.getReason());
        assertEquals(july.getId(), line.getPaymentId());
        assertEquals(3, line.getPeriodCount());
    }

    @Test
    void withoutAMonthAndEverythingPaidItAsksInsteadOfGuessing() {
        RentalAgreement rental = trastero("4", "50.00", LocalDate.of(2026, 7, 1));
        paid(rental, YearMonth.of(2026, 7), LocalDate.of(2026, 7, 1));
        BankMatcher matcher = matcher();

        // A fin de mes: puede ser agosto adelantado o julio otra vez.
        BankImportLine line = line(LocalDate.of(2026, 7, 28), "pago trastero 4", "50.00");
        match(matcher, matcher.load(), line, trasteros());

        assertFalse(line.getAlreadyRecorded());
        assertEquals(BankLineAction.RENT_PAYMENT, line.getAction());
        assertEquals(rental.getId(), line.getRentalAgreement().getId());
        assertNull(line.getPeriodMonth(), "sin mes: tiene que elegirlo una persona");
        assertEquals(BankLineStatus.PENDING, line.getStatus());
    }

    @Test
    void partlyPaidMonthsAreLeftToChoose() {
        RentalAgreement rental = trastero("6", "55.00", LocalDate.of(2024, 1, 1));
        paid(rental, YearMonth.of(2026, 7), LocalDate.of(2026, 6, 2));
        BankMatcher matcher = matcher();

        BankImportLine line = line(LocalDate.of(2026, 7, 17), "julio y agosto trastero 6", "110.00");
        match(matcher, matcher.load(), line, trasteros());

        assertFalse(line.getAlreadyRecorded());
        assertNull(line.getPeriodMonth());
        assertTrue(line.getReason().contains("Julio de 2026 ya está cobrado"), line.getReason());
    }

    @Test
    void unpaidMonthsAreStillProposed() {
        trastero("6", "55.00", LocalDate.of(2024, 1, 1));
        BankMatcher matcher = matcher();

        BankImportLine line = line(LocalDate.of(2026, 7, 17), "julio y agosto trastero 6", "110.00");
        match(matcher, matcher.load(), line, trasteros());

        assertFalse(line.getAlreadyRecorded());
        assertEquals(7, line.getPeriodMonth());
        assertEquals(2, line.getPeriodCount());
    }

    @Test
    void anExpenseEnteredByHandIsRecorded() {
        StorageUnit bajo = StorageUnit.builder().id(1L).name("Bajo delantero").build();
        Expense ibi = Expense.builder().id(7L).storageUnit(bajo).category(ExpenseCategory.TRIBUTOS)
                .amount(new BigDecimal("120.50")).expenseDate(LocalDate.of(2026, 7, 1)).description("IBI 2026").build();
        expenses.add(ibi);
        BankMatcher matcher = matcher();
        BankMatcher.Context ctx = matcher.load();

        BankImportLine first = line(LocalDate.of(2026, 7, 6), "RECIBO IBI CONCELLO DA CORUÑA", "-120.50");
        matcher.propose(first, trasteros(), ctx);
        assertTrue(first.getAlreadyRecorded());
        assertEquals(7L, first.getExpenseId());
        assertEquals(BankLineStatus.DISCARDED, first.getStatus());

        // El mismo gasto no tapa dos cargos.
        BankImportLine second = line(LocalDate.of(2026, 7, 6), "RECIBO IBI CONCELLO DA CORUÑA", "-120.50");
        matcher.propose(second, trasteros(), ctx);
        assertFalse(second.getAlreadyRecorded());
        assertEquals(BankLineAction.EXPENSE, second.getAction());
    }

    @Test
    void monthListsReadLikeAPerson() {
        assertEquals("julio de 2026", BankMatcher.monthsText(List.of(YearMonth.of(2026, 7))));
        assertEquals("julio y agosto de 2026",
                BankMatcher.monthsText(List.of(YearMonth.of(2026, 7), YearMonth.of(2026, 8))));
        assertEquals("noviembre, diciembre de 2026 y enero de 2027", BankMatcher.monthsText(
                List.of(YearMonth.of(2026, 11), YearMonth.of(2026, 12), YearMonth.of(2027, 1))));
    }
}
