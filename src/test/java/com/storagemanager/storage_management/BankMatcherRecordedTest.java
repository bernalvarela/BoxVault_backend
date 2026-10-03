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
import com.storagemanager.storage_management.repository.BankImportLineRepository;
import com.storagemanager.storage_management.repository.BankMatchRuleRepository;
import com.storagemanager.storage_management.repository.ExpenseRepository;
import com.storagemanager.storage_management.repository.OwnershipRepository;
import com.storagemanager.storage_management.repository.PaymentRepository;
import com.storagemanager.storage_management.repository.RentalAgreementRepository;
import com.storagemanager.storage_management.service.bank.BankMatcher;
import com.storagemanager.storage_management.service.bank.TextMatch;
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
    /** Cobros ya casados con movimientos de extractos anteriores. */
    private final java.util.Set<Long> linkedPayments = new java.util.HashSet<>();
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
        BankImportLineRepository lineRepo = mock(BankImportLineRepository.class);
        when(lineRepo.findLinkedPaymentIds()).thenReturn(linkedPayments);
        return new BankMatcher(rentalRepo, paymentRepo, ruleRepo, ownershipRepo, expenseRepo, lineRepo);
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
        assertFalse(line.getReviewSuggested(), "el trastero y los meses lo dicen: no hay que revisarla");
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
    void theEntryPaymentIsTheFirstMonthPlusTheDeposit() {
        // Trastero 4: entra en julio, 50 € al mes y 100 € de fianza. Paga 150 € al
        // firmar (julio + fianza) y después agosto y septiembre por separado.
        RentalAgreement rental = trastero("4", "50.00", LocalDate.of(2026, 7, 1));
        rental.setSecurityDeposit(new BigDecimal("100.00"));
        Payment july = paid(rental, YearMonth.of(2026, 7), LocalDate.of(2026, 7, 6));
        paid(rental, YearMonth.of(2026, 8), LocalDate.of(2026, 8, 5));
        paid(rental, YearMonth.of(2026, 9), LocalDate.of(2026, 9, 8));
        BankMatcher matcher = matcher();
        BankMatcher.Context ctx = matcher.load();

        BankImportLine entry = line(LocalDate.of(2026, 7, 6), "TRANSFERENCIAS pago de trastero número 4", "150.00");
        BankImportLine august = line(LocalDate.of(2026, 8, 5), "trastero 4", "50.00");
        BankImportLine september = line(LocalDate.of(2026, 9, 8), "trastero 4", "50.00");
        // Como la importación: primero la pasada por quien paga, luego la propuesta.
        List<BankImportLine> all = List.of(entry, august, september);
        List<BankImportLine> left = all.stream().filter(l -> !matcher.markRecordedByPayer(l, trasteros(), ctx)).toList();
        left.forEach(l -> matcher.propose(l, trasteros(), ctx));

        assertTrue(entry.getAlreadyRecorded(), entry.getReason());
        assertEquals(july.getId(), entry.getPaymentId());
        assertEquals(1, entry.getPeriodCount());
        assertTrue(entry.getReason().contains("fianza"), entry.getReason());
        assertTrue(august.getAlreadyRecorded());
        assertTrue(september.getAlreadyRecorded());
    }

    @Test
    void whenTheDepositDidNotGoThroughTheBankTheMonthsWin() {
        // La misma entrada, pero la fianza se entregó en mano: los 150 € son
        // julio, agosto y septiembre, apuntados cada uno con su fecha.
        RentalAgreement rental = trastero("4", "50.00", LocalDate.of(2026, 7, 1));
        rental.setSecurityDeposit(new BigDecimal("100.00"));
        Payment july = paid(rental, YearMonth.of(2026, 7), LocalDate.of(2026, 7, 6));
        paid(rental, YearMonth.of(2026, 8), LocalDate.of(2026, 8, 5));
        paid(rental, YearMonth.of(2026, 9), LocalDate.of(2026, 9, 8));
        BankMatcher matcher = matcher();

        BankImportLine line = line(LocalDate.of(2026, 7, 6), "TRANSFERENCIAS pago de trastero número 4", "150.00");
        match(matcher, matcher.load(), line, trasteros());

        assertTrue(line.getAlreadyRecorded(), line.getReason());
        assertEquals(july.getId(), line.getPaymentId());
        assertEquals(3, line.getPeriodCount());
        assertFalse(line.getReason().contains("fianza"), line.getReason());
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
    void aUnitGluedToTheWordBeforeAndAbbreviatedIsStillRecognised() {
        assertEquals(List.of("4"), TextMatch.unitReferences("TRANSFERENCIAS · Pagotrastero nmr 4 Av de Oza"));
        assertEquals(List.of("7"), TextMatch.unitReferences("trastero nº 7"));
        assertEquals(List.of("3"), TextMatch.unitReferences("PAGO TRASTERO NR 3"));
    }

    @Test
    void byAmountOnlyRentalsThatStillOweTheMonthCount() {
        // Tres trasteros de 50 €: dos ya tienen noviembre cobrado.
        LocalDate start = LocalDate.of(2024, 11, 1);
        RentalAgreement a = trastero("1", "50.00", start);
        RentalAgreement b = trastero("2", "50.00", start);
        RentalAgreement c = trastero("3", "50.00", start);
        paid(a, YearMonth.of(2024, 11), LocalDate.of(2024, 11, 25));
        paid(c, YearMonth.of(2024, 11), LocalDate.of(2024, 11, 25));
        BankMatcher matcher = matcher();

        BankImportLine line = line(LocalDate.of(2024, 11, 4), "TRANSFERENCIAS Av de Oza", "50.00");
        match(matcher, matcher.load(), line, trasteros());

        assertEquals(BankLineAction.RENT_PAYMENT, line.getAction());
        assertEquals(b.getId(), line.getRentalAgreement().getId());
        assertEquals(11, line.getPeriodMonth());
        assertTrue(line.getReason().contains("único de los 3"), line.getReason());
    }

    @Test
    void byAmountWhenEveryoneHasPaidItAsks() {
        LocalDate start = LocalDate.of(2024, 11, 1);
        paid(trastero("1", "50.00", start), YearMonth.of(2024, 11), LocalDate.of(2024, 11, 25));
        paid(trastero("2", "50.00", start), YearMonth.of(2024, 11), LocalDate.of(2024, 11, 25));
        BankMatcher matcher = matcher();

        BankImportLine line = line(LocalDate.of(2024, 11, 4), "TRANSFERENCIAS Av de Oza", "50.00");
        match(matcher, matcher.load(), line, trasteros());

        assertEquals(BankLineAction.NONE, line.getAction());
        assertEquals(BankLineStatus.PENDING, line.getStatus());
        assertTrue(line.getReason().contains("ya tienen cobrado"), line.getReason());
    }

    @Test
    void aPaymentRecordedOnTheDueDateIsFoundByItsMonth() {
        // Nélida: el cobro se apunta con fecha del día 1 y el dinero llega el 10.
        RentalAgreement rental = trastero("1", "45.00", LocalDate.of(2024, 1, 1));
        paid(rental, YearMonth.of(2024, 9), LocalDate.of(2024, 9, 1));
        Payment october = paid(rental, YearMonth.of(2024, 10), LocalDate.of(2024, 10, 1));
        BankMatcher matcher = matcher();

        BankImportLine line = line(LocalDate.of(2024, 10, 10), "INGRESO EN EFECTIVO alquiler trastero 1", "45.00");
        match(matcher, matcher.load(), line, trasteros());

        assertTrue(line.getAlreadyRecorded(), line.getReason());
        assertEquals(october.getId(), line.getPaymentId());
    }

    @Test
    void theRecordingDateDoesNotMatterOnlyTheMonth() {
        // Octubre de 2024 apuntado a mano en enero de 2025, al ponerse al día.
        RentalAgreement rental = trastero("1", "45.00", LocalDate.of(2024, 1, 1));
        Payment october = paid(rental, YearMonth.of(2024, 10), LocalDate.of(2025, 1, 15));
        BankMatcher matcher = matcher();

        BankImportLine line = line(LocalDate.of(2024, 10, 10), "ALQUILER TRASTERO 1", "45.00");
        match(matcher, matcher.load(), line, trasteros());

        assertTrue(line.getAlreadyRecorded(), line.getReason());
        assertEquals(october.getId(), line.getPaymentId());
    }

    @Test
    void aPaymentAlreadyLinkedToAnotherStatementIsNotTakenAgain() {
        // Septiembre y octubre ya se casaron con sus transferencias en un extracto
        // anterior: otro ingreso del 15 de octubre no es ninguno de los dos otra vez.
        RentalAgreement rental = trastero("1", "45.00", LocalDate.of(2024, 1, 1));
        for (int m = 4; m <= 8; m++) paid(rental, YearMonth.of(2024, m), LocalDate.of(2024, m, 1));
        Payment september = paid(rental, YearMonth.of(2024, 9), LocalDate.of(2024, 9, 1));
        Payment october = paid(rental, YearMonth.of(2024, 10), LocalDate.of(2024, 10, 1));
        linkedPayments.add(september.getId());
        linkedPayments.add(october.getId());
        BankMatcher matcher = matcher();

        BankImportLine line = line(LocalDate.of(2024, 10, 15), "alquiler trastero 1", "45.00");
        match(matcher, matcher.load(), line, trasteros());

        assertFalse(line.getAlreadyRecorded(), line.getReason());
    }

    @Test
    void aNamedUnitIsNeverMatchedToAnotherTenantsPayment() {
        // "TRASTERO 3" con su cobro ya casado en otro extracto no puede quedarse
        // con el cobro del trastero 6, aunque sea del mismo importe y de esos días.
        RentalAgreement three = trastero("3", "55.00", LocalDate.of(2025, 1, 1));
        RentalAgreement six = trastero("6", "55.00", LocalDate.of(2024, 1, 1));
        for (int m = 3; m <= 9; m++) {
            Payment p = paid(three, YearMonth.of(2026, m), LocalDate.of(2026, m, 5));
            linkedPayments.add(p.getId());
        }
        paid(six, YearMonth.of(2026, 10), LocalDate.of(2026, 9, 14));
        BankMatcher matcher = matcher();

        BankImportLine line = line(LocalDate.of(2026, 9, 7), "MENSUALIDAD MES TRASTERO 3", "55.00");
        match(matcher, matcher.load(), line, trasteros());

        assertFalse(line.getAlreadyRecorded(), line.getReason());
        assertEquals(three.getId(), line.getRentalAgreement().getId());
    }

    @Test
    void byAmountTheOnlyContractWithAFreePaymentThatMonthIsThePayer() {
        // "TRASTERO MES AGOSTO", 55 €: tres contratos de 55 € tienen agosto
        // cobrado, pero dos ya están casados con sus transferencias.
        LocalDate start = LocalDate.of(2026, 8, 1);
        Payment a = paid(trastero("1", "55.00", start), YearMonth.of(2026, 8), LocalDate.of(2026, 8, 1));
        Payment b = paid(trastero("2", "55.00", start), YearMonth.of(2026, 8), LocalDate.of(2026, 8, 1));
        Payment c = paid(trastero("3", "55.00", start), YearMonth.of(2026, 8), LocalDate.of(2026, 8, 1));
        linkedPayments.add(a.getId());
        linkedPayments.add(c.getId());
        BankMatcher matcher = matcher();

        BankImportLine line = line(LocalDate.of(2026, 8, 4), "TRANSFERENCIAS TRASTERO MES AGOSTO", "55.00");
        match(matcher, matcher.load(), line, trasteros());

        assertTrue(line.getAlreadyRecorded(), line.getReason());
        assertEquals(b.getId(), line.getPaymentId());
        assertTrue(line.getReviewSuggested(), "casada solo por el importe: queda para revisar");
    }

    @Test
    void anAmountMatchConfirmedByTheUnitNumberNeedsNoReview() {
        // "Alquiler Pasaxe num 1.": ninguna palabra de unidad, pero el "1" es el
        // del trastero cuyo cobro se encontró por el importe y la fecha.
        RentalAgreement one = trastero("1", "55.00", LocalDate.of(2025, 1, 1));
        Payment february = paid(one, YearMonth.of(2026, 2), LocalDate.of(2026, 2, 3));
        BankMatcher matcher = matcher();

        BankImportLine line = line(LocalDate.of(2026, 2, 3), "TRANSFERENCIAS Alquiler Pasaxe num 1.", "55.00");
        match(matcher, matcher.load(), line, trasteros());

        assertTrue(line.getAlreadyRecorded(), line.getReason());
        assertEquals(february.getId(), line.getPaymentId());
        assertFalse(line.getReviewSuggested(), line.getReason());

        // "trstr" ya es una palabra de unidad: "trstr num 1" es el trastero 1.
        assertEquals(List.of("1"), TextMatch.unitReferences("Alqulr trstr num 1."));
        // Pero no se saltan palabras entre la unidad y el número: "Pasaxe 29" es
        // la calle, no el trastero 29. Ahí es el "1" suelto el que confirma el cobro.
        assertEquals(List.of(), TextMatch.unitReferences("Alqulr trstr Pasaxe num 1."));
        assertEquals(List.of(), TextMatch.unitReferences("trastero Pasaxe 29"));
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
