package com.storagemanager.storage_management.service.bank;

import com.storagemanager.storage_management.model.BankImportLine;
import com.storagemanager.storage_management.model.BankImportProfile;
import com.storagemanager.storage_management.model.BankImportLineSplit;
import com.storagemanager.storage_management.model.BankMatchRule;
import com.storagemanager.storage_management.model.BankMatchRuleSplit;
import com.storagemanager.storage_management.model.Client;
import com.storagemanager.storage_management.model.Expense;
import com.storagemanager.storage_management.model.Owner;
import com.storagemanager.storage_management.model.Ownership;
import com.storagemanager.storage_management.model.Payment;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.RentalParty;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.model.enums.BankLineAction;
import com.storagemanager.storage_management.model.enums.BankLineStatus;
import com.storagemanager.storage_management.model.enums.BankProfileContext;
import com.storagemanager.storage_management.model.enums.CommunityEntryType;
import com.storagemanager.storage_management.model.enums.ExpenseCategory;
import com.storagemanager.storage_management.model.enums.PaymentStatus;
import com.storagemanager.storage_management.repository.BankMatchRuleRepository;
import com.storagemanager.storage_management.repository.ExpenseRepository;
import com.storagemanager.storage_management.repository.OwnershipRepository;
import com.storagemanager.storage_management.repository.PaymentRepository;
import com.storagemanager.storage_management.repository.RentalAgreementRepository;
import com.storagemanager.storage_management.service.pdf.Pdfs;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Propone qué es cada movimiento de un extracto. No crea nada: deja la
 * propuesta en la línea, con su motivo, para que una persona la revise.
 * <p>
 * El orden de las preguntas importa:
 * <ol>
 *   <li>¿Lo dice una regla aprendida? Lo que ya corrigió alguien una vez manda
 *       sobre cualquier suposición.</li>
 *   <li>Si entra dinero en una cuenta de los propietarios: ¿es el cobro de una
 *       mensualidad? Se busca al inquilino (o al fiador) por su nombre en el
 *       concepto y, si no sale, por el importe. Y antes de proponer un cobro,
 *       se mira si ya está apuntado a mano.</li>
 *   <li>Si sale dinero de una cuenta de los propietarios: es un gasto, con la
 *       categoría que digan sus palabras (IBI, AEAT, Iberdrola, seguro...).</li>
 *   <li>Si la cuenta es de la comunidad: una cuota del propietario que se
 *       reconozca, o un gasto del edificio.</li>
 * </ol>
 * Lo que no se reconoce queda como "nada", a la vista, para que se decida.
 */
@Component
@RequiredArgsConstructor
public class BankMatcher {

    /** Días de margen para dar un cobro por "ya apuntado a mano". */
    private static final int ALREADY_RECORDED_DAYS = 7;
    /**
     * Días de margen para un pago de varios meses apuntado a mano como varios
     * cobros del mismo día: que sumen justo el importe ya es una señal fuerte, y
     * es fácil apuntarlos con la fecha en que se registraron, no la del banco.
     */
    private static final int MULTI_MONTH_DAYS = 31;
    /** Días de margen para dar un gasto por "ya apuntado a mano": el IBI se apunta a veces con la fecha del recibo. */
    private static final int EXPENSE_RECORDED_DAYS = 10;
    /** Hasta cuántos meses atrás se busca una mensualidad sin cobrar. */
    private static final int MONTHS_BACK = 6;

    private final RentalAgreementRepository rentals;
    private final PaymentRepository payments;
    private final BankMatchRuleRepository rules;
    private final OwnershipRepository ownerships;
    private final ExpenseRepository expenses;

    /**
     * Lo que hace falta para proponer, cargado una vez por extracto.
     *
     * @param claimedPayments los cobros hechos a mano que ya se han dado por
     *                        "esta fila es este cobro": uno no puede tapar dos filas
     * @param claimedExpenses lo mismo con los gastos
     */
    public record Context(List<RentalAgreement> rentals, Map<Long, List<Payment>> paymentsByRental,
                         List<Payment> paidPayments, List<BankMatchRule> rules, List<Ownership> ownerships,
                         Set<Long> claimedPayments, List<Expense> expenses, Set<Long> claimedExpenses) {}

    public Context load() {
        List<Payment> all = payments.findAll();
        Map<Long, List<Payment>> byRental = all.stream()
                .filter(p -> p.getRentalAgreement() != null)
                .collect(Collectors.groupingBy(p -> p.getRentalAgreement().getId()));
        List<Payment> paid = all.stream()
                .filter(p -> p.getStatus() == PaymentStatus.PAID && p.getPaymentDate() != null && p.getAmountPaid() != null)
                .toList();
        // Las reglas más largas primero: "PEREZ SOUTO CARMEN" es más concreta que "PEREZ".
        List<BankMatchRule> sorted = rules.findAll().stream()
                .sorted(Comparator.comparingInt((BankMatchRule r) -> r.getPattern().split(" ").length).reversed())
                .toList();
        return new Context(rentals.findAll(), byRental, paid, sorted, ownerships.findAll(), new java.util.HashSet<>(),
                expenses.findAll(), new java.util.HashSet<>());
    }

    /** Deja en la línea la propuesta y su motivo. */
    public void propose(BankImportLine line, BankImportProfile profile, Context ctx) {
        clearProposal(line);
        Set<String> words = TextMatch.words(line.getConcept());

        for (BankMatchRule rule : ctx.rules()) {
            if (TextMatch.matchesRule(rule.getPattern(), words) && applyRule(line, rule, profile, ctx)) {
                if (line.getAction() == BankLineAction.EXPENSE) markExpenseRecorded(line, ctx);
                return;
            }
        }

        // Un cargo que ya está apuntado a mano como gasto (el IBI, un recibo que
        // se metió antes de importar) no se vuelve a apuntar.
        if (!line.isIncome() && profile.getContext() == BankProfileContext.PROPIETARIOS
                && markExpenseRecorded(line, ctx)) {
            return;
        }

        // La cuota de una hipoteca o un préstamo: se apunta entera como gasto
        // «Hipoteca», y en el gasto se dicen después los intereses de ese mes (del
        // cuadro de amortización), que son lo único deducible en el IRPF.
        if (!line.isIncome() && profile.getContext() == BankProfileContext.PROPIETARIOS
                && TextMatch.containsAny(TextMatch.normalize(line.getConcept()), TextMatch.vocabulary().loan())) {
            line.setAction(BankLineAction.EXPENSE);
            line.setExpenseCategory(ExpenseCategory.HIPOTECA);
            line.setStorageUnit(profile.getDefaultUnit());
            line.setReason("Cuota de hipoteca o préstamo" + (line.getStorageUnit() == null ? ": elige la unidad" : "")
                    + ". Después, en Gastos, pon los intereses de la cuota (del cuadro de amortización): "
                    + "es lo único deducible en el IRPF");
            return;
        }

        // Una fianza no es una mensualidad ni un gasto: es dinero del inquilino
        // que se le devuelve. Se lleva en la pestaña de la fianza del contrato.
        if (TextMatch.containsAny(TextMatch.normalize(line.getConcept()), TextMatch.vocabulary().deposit())) {
            line.setAction(BankLineAction.NONE);
            line.setStatus(BankLineStatus.DISCARDED);
            line.setReason(line.isIncome()
                    ? "Fianza: no es una mensualidad. Márcala como cobrada en la pestaña Fianza del contrato"
                    : "Devolución de fianza: no es un gasto. Se apunta al finalizar el contrato");
            return;
        }

        // Un ingreso que ya está apuntado a mano como cobro no se vuelve a apuntar.
        // Los reconocidos por el trastero o el nombre se casaron en la primera
        // pasada (markRecordedByPayer) y los de una regla, justo arriba; aquí
        // queda el caso sin nada que lo identifique, solo por importe y fecha.
        if (line.isIncome() && profile.getContext() == BankProfileContext.PROPIETARIOS
                && markRecordedByAmount(line, ctx)) {
            return;
        }

        if (profile.getContext() == BankProfileContext.COMUNIDAD) {
            proposeCommunity(line, words, ctx);
        } else if (line.isIncome()) {
            proposeRentPayment(line, words, profile, ctx);
        } else {
            proposeExpense(line, profile, ctx);
        }
    }

    // ------------------------------------------------------------- Reglas

    private boolean applyRule(BankImportLine line, BankMatchRule rule, BankImportProfile profile, Context ctx) {
        String why = "Regla aprendida: «" + rule.getPattern().toLowerCase() + "»";
        switch (rule.getAction()) {
            case RENT_PAYMENT -> {
                if (!line.isIncome() || rule.getClient() == null) return false;
                RentalAgreement rental = rentalOfClient(rule.getClient(), line.getDate(), ctx);
                if (rental == null) return false;
                // Quien paga no firma el contrato (la madre que paga el piso del
                // hijo): la primera pasada no lo reconoció por el nombre, así que
                // aquí se mira si ese cobro ya está apuntado a mano en su contrato.
                Payment recorded = recordedCandidates(line, ctx).stream()
                        .filter(p -> p.getRentalAgreement() != null && p.getRentalAgreement().getId().equals(rental.getId()))
                        .min(Comparator.comparingLong(p -> Math.abs(p.getPaymentDate().toEpochDay() - line.getDate().toEpochDay())))
                        .orElse(null);
                if (recorded != null) {
                    markRecorded(line, List.of(recorded), ctx, " (por una regla aprendida)");
                    return true;
                }
                List<Payment> group = recordedMultiMonth(line, rental, ctx);
                if (!group.isEmpty()) {
                    markRecorded(line, group, ctx, " (por una regla aprendida)");
                    return true;
                }
                proposePayment(line, rental, why, ctx);
                return true;
            }
            case EXPENSE -> {
                if (line.isIncome()) return false;
                line.setAction(BankLineAction.EXPENSE);
                line.setExpenseCategory(rule.getExpenseCategory());
                if (rule.getSplits() != null && !rule.getSplits().isEmpty()) {
                    splitByShares(line, rule.getSplits());
                    line.setReason(why + " (repartido entre " + rule.getSplits().size() + " unidades)");
                    return true;
                }
                line.setStorageUnit(rule.getStorageUnit() != null ? rule.getStorageUnit() : profile.getDefaultUnit());
                line.setReason(why);
                return true;
            }
            case COMMUNITY_ENTRY -> {
                if (rule.getCommunityEntryType() == null || rule.getCommunityEntryType().isIncome() != line.isIncome()) {
                    return false;
                }
                line.setAction(BankLineAction.COMMUNITY_ENTRY);
                line.setCommunityEntryType(rule.getCommunityEntryType());
                if (rule.getSplits() != null && !rule.getSplits().isEmpty()) {
                    splitByShares(line, rule.getSplits());
                    line.setReason(why + " (repartido entre " + rule.getSplits().size() + " unidades)");
                    return true;
                }
                line.setStorageUnit(rule.getStorageUnit());
                line.setReason(why);
                return true;
            }
            case NONE -> {
                line.setAction(BankLineAction.NONE);
                line.setStatus(BankLineStatus.DISCARDED);
                line.setReason(why + " (no se apunta)");
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------- Cobros

    private void proposeRentPayment(BankImportLine line, Set<String> words, BankImportProfile profile, Context ctx) {
        LocalDate date = line.getDate();

        Identified payer = identify(line, words, profile, ctx);
        if (payer != null) {
            proposePayment(line, payer.rental(), payer.why(), ctx);
            return;
        }

        // Por el importe: los contratos en vigor ese mes que cobran eso y todavía
        // deben ese mes (o, si el concepto no lo dice, alguno de los últimos). Uno
        // que ya lo tiene cobrado no puede ser quien paga.
        List<RentalAgreement> byAmount = ctx.rentals().stream()
                .filter(r -> inForceAround(r, date) && sameAmount(r, line))
                .toList();
        YearMonth named = namedPeriod(line);
        List<RentalAgreement> owing = byAmount.stream().filter(r -> owes(r, line, named, ctx)).toList();
        if (owing.size() == 1) {
            proposePayment(line, owing.get(0), "Por el importe (no sale ningún nombre conocido)"
                    + (byAmount.size() > 1 ? ": es el único de los " + byAmount.size() + " contratos de "
                        + Pdfs.euros(line.getAmount()) + " con " + (named != null ? monthText(named) : "una mensualidad")
                        + " sin cobrar" : "") + ". Compruébalo", ctx);
            return;
        }

        line.setAction(BankLineAction.NONE);
        if (byAmount.isEmpty()) {
            // Ni nombre, ni unidad, ni un contrato que cobre eso: casi siempre algo
            // personal (una nómina, una devolución). Se ignora, a la vista en
            // «Ignoradas» por si acaso.
            line.setStatus(BankLineStatus.DISCARDED);
            line.setReason("Ingreso sin reconocer: si es un cobro del alquiler, recupéralo y elige el contrato");
        } else if (owing.isEmpty()) {
            line.setReason(truncate(byAmount.size() == 1
                    ? "El único contrato de " + Pdfs.euros(line.getAmount()) + " (" + rentalShort(byAmount.get(0))
                        + ") ya tiene cobrado " + (named != null ? monthText(named) : "todo hasta este mes")
                        + ": ¿es un cobro ya apuntado? Si no, elige el contrato y el mes"
                    : "Los " + byAmount.size() + " contratos de " + Pdfs.euros(line.getAmount()) + " ya tienen cobrado "
                        + (named != null ? monthText(named) : "todo hasta este mes")
                        + ": ¿es un cobro ya apuntado? Si no, elige el contrato y el mes"));
        } else {
            line.setReason(truncate(owing.size() + " contratos de " + Pdfs.euros(line.getAmount())
                    + " tienen la mensualidad sin cobrar: " + owing.stream().map(BankMatcher::rentalShort)
                        .collect(Collectors.joining(", ")) + ". Elige cuál es"));
        }
    }

    /**
     * Si al contrato le falta por cobrar el mes que nombra el concepto o, si no
     * nombra ninguno, alguno de los últimos meses hasta el del movimiento.
     */
    private static boolean owes(RentalAgreement rental, BankImportLine line, YearMonth named, Context ctx) {
        List<Payment> paid = ctx.paymentsByRental().getOrDefault(rental.getId(), List.of());
        if (named == null) return firstOpenPeriod(rental, line.getDate(), paid) != null;
        if (rental.getStartDate() != null && named.isBefore(YearMonth.from(rental.getStartDate()))) return false;
        if (rental.getEndDate() != null && named.isAfter(YearMonth.from(rental.getEndDate()))) return false;
        return !isClosed(byPeriod(paid).get(named));
    }

    /** "RNT-2026-015 (Trastero 4)", para nombrar un contrato en un motivo. */
    private static String rentalShort(RentalAgreement rental) {
        return rental.getAgreementNumber()
                + (rental.getStorageUnit() == null ? "" : " (" + rental.getStorageUnit().getName() + ")");
    }

    /** A quién se le reconoce el pago y por qué. */
    private record Identified(RentalAgreement rental, String why) {}

    /**
     * Quién paga, por lo que dice el concepto: primero el número de la unidad
     * ("TRASTERO 8", "Baixo 7"), que es lo que la gente escribe; si no, el
     * nombre de quien firma el contrato (arrendatarios y fiadores).
     */
    private Identified identify(BankImportLine line, Set<String> words, BankImportProfile profile, Context ctx) {
        LocalDate date = line.getDate();
        String normalizedConcept = TextMatch.normalize(line.getConcept());

        for (String number : TextMatch.unitReferences(line.getConcept())) {
            List<RentalAgreement> byUnit = ctx.rentals().stream()
                    .filter(r -> inForceAround(r, date) && r.getStorageUnit() != null
                            && number.equalsIgnoreCase(r.getStorageUnit().getUnitNumber()))
                    .toList();
            // Un "8" puede ser el trastero 8 del bajo o una unidad 8 de otro
            // edificio: mandan los que cuelgan de la unidad de la cuenta.
            if (byUnit.size() > 1 && profile.getDefaultUnit() != null) {
                List<RentalAgreement> underAccount = byUnit.stream()
                        .filter(r -> isUnder(r.getStorageUnit(), profile.getDefaultUnit())).toList();
                if (!underAccount.isEmpty()) byUnit = underAccount;
            }
            if (byUnit.size() > 1) {
                // Dos contratos de la misma unidad cerca de la fecha (uno que se va y
                // otro que entra): el que esté en vigor ese día.
                List<RentalAgreement> onDate = byUnit.stream().filter(r -> inForceOn(r, date)).toList();
                if (!onDate.isEmpty()) byUnit = onDate;
            }
            if (!byUnit.isEmpty()) {
                RentalAgreement rental = byUnit.get(0);
                return new Identified(rental, "Por «" + unitWordOf(rental) + " " + number + "» en el concepto");
            }
        }

        RentalAgreement best = null;
        String bestName = null;
        int bestScore = 0;
        for (RentalAgreement rental : ctx.rentals()) {
            if (!inForceAround(rental, date)) continue;
            for (Client person : signers(rental)) {
                int score = TextMatch.nameScore(person.getFullName(), words);
                // El NIF se busca como frase: normalizado, "12345678Z" son dos palabras.
                boolean docInConcept = person.getDocumentId() != null && !person.getDocumentId().isBlank()
                        && TextMatch.containsAny(normalizedConcept, List.of(TextMatch.normalize(person.getDocumentId())));
                if (docInConcept) score = Math.max(score, 10);
                boolean better = score > bestScore
                        || (score == bestScore && score > 0 && sameAmount(rental, line) && !sameAmount(best, line));
                if (better) {
                    best = rental;
                    bestScore = score;
                    bestName = person.getFullName();
                }
            }
        }
        return best == null ? null : new Identified(best, "Por el nombre de " + bestName);
    }

    /**
     * Propone el cobro de ese contrato: cuántas mensualidades cubre el importe
     * (110 € con una mensualidad de 55 € son dos) y desde qué mes. El mes es el
     * que nombre el concepto ("OCTUBRE 2026", "septiembre y octubre") o, si no
     * nombra ninguno, la mensualidad más antigua sin cobrar.
     * <p>
     * Si esos meses ya están cobrados, el pago ya está apuntado: se da por
     * registrado. Si lo están solo algunos, o el concepto no dice el mes y no
     * queda ninguno sin cobrar hasta la fecha, no se adivina: se deja sin mes
     * para que se elija (puede ser un pago adelantado o el mismo pago otra vez).
     */
    private void proposePayment(BankImportLine line, RentalAgreement rental, String why, Context ctx) {
        List<Payment> paid = ctx.paymentsByRental().getOrDefault(rental.getId(), List.of());
        line.setAction(BankLineAction.RENT_PAYMENT);
        line.setRentalAgreement(rental);

        if (entryWithDeposit(line, rental, paid, why, ctx)) return;

        int count = monthsCovered(rental, line);
        line.setPeriodCount(count);
        YearMonth named = namedPeriod(line);
        YearMonth period = named != null ? named : firstOpenPeriod(rental, line.getDate(), paid);
        if (period == null) {
            YearMonth current = YearMonth.from(line.getDate());
            // Varios meses seguidos ya cobrados que llegan más allá del mes del
            // movimiento: nadie paga agosto y septiembre a 6 de julio salvo con un
            // pago adelantado, y ese pago de tantos meses es esta transferencia,
            // aunque cada cobro se apuntase con otra fecha.
            List<Payment> ahead = settledRunReachingFuture(paid, current, count, ctx);
            if (!ahead.isEmpty()) {
                markRecorded(line, ahead, ctx, " (pago adelantado: esos meses ya están cobrados)");
                return;
            }
            line.setReason(truncate(why + ". Las mensualidades hasta " + monthText(current)
                    + " ya están cobradas: si es un pago adelantado, elige el mes; si es el cobro ya apuntado, descarta la fila"));
            return;
        }

        Map<YearMonth, Payment> byPeriod = byPeriod(paid);
        List<YearMonth> covered = new ArrayList<>();
        for (int i = 0; i < count; i++) covered.add(period.plusMonths(i));
        List<YearMonth> closed = covered.stream().filter(ym -> isClosed(byPeriod.get(ym))).toList();
        if (!closed.isEmpty() && closed.size() == covered.size()
                && closed.stream().allMatch(ym -> isSettled(byPeriod.get(ym)))) {
            markRecorded(line, covered.stream().map(byPeriod::get).toList(), ctx,
                    " (" + (closed.size() == 1 ? "ese mes ya está cobrado" : "esos meses ya están cobrados") + ")");
            return;
        }
        if (!closed.isEmpty()) {
            line.setReason(truncate(why + ". " + capitalize(monthsText(closed))
                    + (closed.size() == 1 ? " ya está cobrado o no se cobra" : " ya están cobrados o no se cobran")
                    + ": elige el mes y el número de meses, o descarta la fila si es un cobro ya apuntado"));
            return;
        }

        line.setPeriodYear(period.getYear());
        line.setPeriodMonth(period.getMonthValue());

        StringBuilder reason = new StringBuilder(why);
        if (count > 1) {
            reason.append(". ").append(count).append(" mensualidades de ")
                    .append(Pdfs.euros(rental.getMonthlyCharge()));
        } else if (!sameAmount(rental, line)) {
            reason.append(". El importe no es el de la mensualidad (")
                    .append(Pdfs.euros(rental.getMonthlyCharge())).append(")");
        }
        if (named != null) reason.append(". El mes, del concepto");
        line.setReason(truncate(reason.toString()));
    }

    /**
     * El pago de entrada: al firmar se paga la fianza y el primer mes (o los
     * primeros) en una sola transferencia. 150 € en un trastero de 50 € con
     * 100 € de fianza no son tres meses: son julio y la fianza. Solo cerca del
     * inicio del contrato y si el importe es justo la fianza más meses enteros.
     * <p>
     * Si esos meses ya están cobrados, la fila es ese cobro. Si no, se deja sin
     * decidir: un cobro del banco no puede llevarse la fianza dentro, que no es
     * una mensualidad ni un ingreso.
     * <p>
     * Pero la fianza no siempre pasa por el banco (a veces se entrega en mano, o
     * se usa para devolver la de otro inquilino): si el importe entero son meses
     * ya apuntados, julio, agosto y septiembre por 150 €, manda eso, y la fianza
     * ni se menciona.
     *
     * @return si la fila era un pago de entrada
     */
    private boolean entryWithDeposit(BankImportLine line, RentalAgreement rental, List<Payment> paid, String why, Context ctx) {
        java.math.BigDecimal deposit = rental.getSecurityDeposit();
        java.math.BigDecimal charge = rental.getMonthlyCharge();
        LocalDate start = rental.getStartDate();
        if (deposit == null || deposit.signum() <= 0 || charge.signum() <= 0 || start == null) return false;
        if (line.getDate().isBefore(start.minusDays(30)) || line.getDate().isAfter(start.plusDays(45))) return false;
        if (allMonthsRecorded(line, rental, paid, ctx)) return false;
        java.math.BigDecimal rest = line.getAmount().subtract(deposit);
        if (rest.signum() < 0) return false;
        java.math.BigDecimal[] division = rest.divideAndRemainder(charge);
        if (division[1].signum() != 0 || division[0].intValue() > 12) return false;
        int months = division[0].intValue();

        String depositText = "la fianza de " + Pdfs.euros(deposit);
        // Lo que queda por hacer con la fianza: nada si ya consta como cobrada.
        boolean depositPaid = Boolean.TRUE.equals(rental.getDepositPaid());
        String depositTodo = depositPaid
                ? "la fianza ya consta como cobrada en el contrato"
                : "márcala como cobrada en la pestaña Fianza del contrato";
        if (months == 0) {
            line.setAction(BankLineAction.NONE);
            line.setStatus(BankLineStatus.DISCARDED);
            line.setReason(truncate(why + ". Es " + depositText + ": no es una mensualidad; " + depositTodo));
            return true;
        }

        Map<YearMonth, Payment> byPeriod = byPeriod(paid);
        YearMonth first = YearMonth.from(start);
        List<Payment> recorded = new ArrayList<>();
        for (int i = 0; i < months; i++) {
            Payment p = byPeriod.get(first.plusMonths(i));
            if (isSettled(p) && !ctx.claimedPayments().contains(p.getId())) recorded.add(p);
        }
        if (recorded.size() == months) {
            markRecorded(line, recorded, ctx, " más " + depositText
                    + " (pago de entrada; " + depositTodo + ")");
            return true;
        }
        List<YearMonth> covered = new ArrayList<>();
        for (int i = 0; i < months; i++) covered.add(first.plusMonths(i));
        line.setAction(BankLineAction.NONE);
        line.setRentalAgreement(null);
        StringBuilder reason = new StringBuilder(why).append(". Puede ser el pago de entrada: ")
                .append(monthsText(covered)).append(" más ").append(depositText);
        int asMonths = monthsCovered(rental, line);
        if (asMonths > 1) {
            List<YearMonth> alternative = new ArrayList<>();
            for (int i = 0; i < asMonths; i++) alternative.add(first.plusMonths(i));
            reason.append(", o ").append(monthsText(alternative)).append(" si la fianza no pasó por el banco");
        }
        reason.append(depositPaid
                ? ". Elige el cobro o, si lleva la fianza (ya consta como cobrada), apunta el mes en Cobros y descarta la fila"
                : ". Elige el cobro o, si lleva fianza, apunta el mes en Cobros y la fianza en su pestaña, y descarta la fila");
        line.setReason(truncate(reason.toString()));
        return true;
    }

    /**
     * Si el importe entero son mensualidades ya cobradas desde el inicio del
     * contrato (y sin casar con otra fila): 150 € = julio, agosto y septiembre.
     */
    private static boolean allMonthsRecorded(BankImportLine line, RentalAgreement rental, List<Payment> paid, Context ctx) {
        int count = monthsCovered(rental, line);
        if (count < 1 || line.getAmount().compareTo(rental.getMonthlyCharge().multiply(java.math.BigDecimal.valueOf(count))) != 0) {
            return false;
        }
        Map<YearMonth, Payment> byPeriod = byPeriod(paid);
        YearMonth first = YearMonth.from(rental.getStartDate());
        for (int i = 0; i < count; i++) {
            Payment p = byPeriod.get(first.plusMonths(i));
            if (!isSettled(p) || ctx.claimedPayments().contains(p.getId())) return false;
        }
        return true;
    }

    /** Cuántas mensualidades enteras son el importe: 1 si no es un múltiplo exacto. */
    private static int monthsCovered(RentalAgreement rental, BankImportLine line) {
        java.math.BigDecimal charge = rental.getMonthlyCharge();
        if (charge == null || charge.signum() <= 0) return 1;
        java.math.BigDecimal[] division = line.getAmount().divideAndRemainder(charge);
        if (division[1].signum() != 0) return 1;
        int count = division[0].intValue();
        return count >= 1 && count <= 12 ? count : 1;
    }

    /**
     * El primer mes que nombra el concepto, con el año que diga o, si no dice
     * ninguno, el más cercano a la fecha del movimiento: "DICIEMBRE" pagado el
     * 2 de enero es el diciembre anterior.
     */
    private static YearMonth namedPeriod(BankImportLine line) {
        List<Integer> months = TextMatch.monthsIn(line.getConcept());
        if (months.isEmpty()) return null;
        int month = months.get(0);
        Integer year = TextMatch.yearIn(line.getConcept());
        if (year != null) return YearMonth.of(year, month);
        YearMonth around = YearMonth.from(line.getDate());
        YearMonth best = null;
        for (int y = around.getYear() - 1; y <= around.getYear() + 1; y++) {
            YearMonth candidate = YearMonth.of(y, month);
            if (best == null || Math.abs(monthsBetween(candidate, around)) < Math.abs(monthsBetween(best, around))) {
                best = candidate;
            }
        }
        return best;
    }

    private static long monthsBetween(YearMonth a, YearMonth b) {
        return (long) (a.getYear() - b.getYear()) * 12 + (a.getMonthValue() - b.getMonthValue());
    }

    // ------------------------------------------------------------- Ya apuntados a mano

    /**
     * Primera pasada, antes de proponer nada: los ingresos cuyo pagador se
     * reconoce (por el trastero que nombra o por el nombre) y que ya tienen su
     * cobro apuntado a mano en ese contrato: mismo importe, fecha a pocos días.
     * Va antes que el resto para que un ingreso sin nada que lo identifique no
     * se quede con el cobro de otro que paga lo mismo.
     *
     * @return si la fila era un cobro ya apuntado
     */
    public boolean markRecordedByPayer(BankImportLine line, BankImportProfile profile, Context ctx) {
        if (!line.isIncome() || profile.getContext() != BankProfileContext.PROPIETARIOS) return false;
        Identified payer = identify(line, TextMatch.words(line.getConcept()), profile, ctx);
        if (payer == null) return false;
        Payment recorded = recordedCandidates(line, ctx).stream()
                .filter(p -> p.getRentalAgreement() != null
                        && p.getRentalAgreement().getId().equals(payer.rental().getId()))
                .min(Comparator.comparingLong(p -> Math.abs(p.getPaymentDate().toEpochDay() - line.getDate().toEpochDay())))
                .orElse(null);
        if (recorded != null) {
            markRecorded(line, List.of(recorded), ctx, "");
            return true;
        }
        List<Payment> group = recordedMultiMonth(line, payer.rental(), ctx);
        if (group.isEmpty()) return false;
        markRecorded(line, group, ctx, "");
        return true;
    }

    /**
     * Un pago de varios meses apuntado a mano: varios cobros del contrato con la
     * misma fecha de pago, cerca del movimiento, que suman justo su importe
     * (julio y agosto, 55 + 55, por una transferencia de 110 €). Si hay varios
     * grupos así, el de la fecha más cercana.
     */
    private static List<Payment> recordedMultiMonth(BankImportLine line, RentalAgreement rental, Context ctx) {
        Map<LocalDate, List<Payment>> byDate = ctx.paidPayments().stream()
                .filter(p -> !ctx.claimedPayments().contains(p.getId()))
                .filter(p -> p.getRentalAgreement() != null && p.getRentalAgreement().getId().equals(rental.getId()))
                .filter(p -> Math.abs(p.getPaymentDate().toEpochDay() - line.getDate().toEpochDay()) <= MULTI_MONTH_DAYS)
                .collect(Collectors.groupingBy(Payment::getPaymentDate));
        return byDate.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .filter(e -> e.getValue().stream().map(Payment::getAmountPaid)
                        .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)
                        .compareTo(line.getAmount()) == 0)
                .min(Comparator.comparingLong(e -> Math.abs(e.getKey().toEpochDay() - line.getDate().toEpochDay())))
                .map(Map.Entry::getValue)
                .orElse(List.of());
    }

    /**
     * Segunda pasada: un ingreso sin nombre reconocible que coincide en importe
     * y fecha con un único cobro apuntado a mano y todavía libre. Se da por ese,
     * avisando de que hay que comprobarlo; si hay varios posibles, no se elige.
     */
    private boolean markRecordedByAmount(BankImportLine line, Context ctx) {
        List<Payment> candidates = recordedCandidates(line, ctx);
        if (candidates.size() != 1) return false;
        markRecorded(line, List.of(candidates.get(0)), ctx, " (por importe y fecha: compruébalo)");
        return true;
    }

    /** Los cobros apuntados a mano con ese importe, a pocos días y sin casar todavía con otra fila. */
    private static List<Payment> recordedCandidates(BankImportLine line, Context ctx) {
        return ctx.paidPayments().stream()
                .filter(p -> !ctx.claimedPayments().contains(p.getId()))
                .filter(p -> p.getAmountPaid().compareTo(line.getAmount()) == 0)
                .filter(p -> Math.abs(p.getPaymentDate().toEpochDay() - line.getDate().toEpochDay()) <= ALREADY_RECORDED_DAYS)
                .toList();
    }

    /**
     * La fila es uno o varios cobros ya apuntados (varios si pagó varios meses
     * de una vez): queda enlazada al primero y se ignora al aplicar.
     */
    private static void markRecorded(BankImportLine line, List<Payment> recorded, Context ctx, String note) {
        List<Payment> sorted = recorded.stream()
                .sorted(Comparator.comparing(BankMatcher::periodOf, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        Payment first = sorted.get(0);
        clearProposal(line);
        sorted.forEach(p -> ctx.claimedPayments().add(p.getId()));
        line.setAction(BankLineAction.RENT_PAYMENT);
        line.setRentalAgreement(first.getRentalAgreement());
        line.setPeriodYear(first.getBillingPeriodYear());
        line.setPeriodMonth(first.getBillingPeriodMonth());
        line.setPeriodCount(sorted.size());
        line.setPaymentId(first.getId());
        line.setAlreadyRecorded(true);
        line.setStatus(BankLineStatus.DISCARDED);
        List<YearMonth> months = sorted.stream().map(BankMatcher::periodOf).filter(Objects::nonNull).toList();
        String when = first.getPaymentDate() == null ? "" : " del " + Pdfs.day(first.getPaymentDate());
        line.setReason(truncate("Ya registrado a mano: el cobro de " + monthsText(months) + when + note
                + ". Se ignora para no contarlo dos veces"));
    }

    // ------------------------------------------------------------- Gastos ya apuntados

    /**
     * Un cargo que ya está apuntado a mano como gasto: mismo importe y fecha a
     * pocos días, o varios gastos del mismo día que suman el cargo (uno
     * repartido a mano entre unidades). Si hay varios, manda el de la misma
     * categoría que la propuesta y después el de la fecha más cercana.
     *
     * @return si la fila era un gasto ya apuntado
     */
    private boolean markExpenseRecorded(BankImportLine line, Context ctx) {
        if (line.isIncome()) return false;
        java.math.BigDecimal total = line.getAmount().abs();
        LocalDate date = line.getDate();
        ExpenseCategory proposed = line.getExpenseCategory();
        List<Expense> near = ctx.expenses().stream()
                .filter(e -> !ctx.claimedExpenses().contains(e.getId()))
                .filter(e -> e.getExpenseDate() != null && e.getAmount() != null)
                .filter(e -> Math.abs(e.getExpenseDate().toEpochDay() - date.toEpochDay()) <= EXPENSE_RECORDED_DAYS)
                .toList();
        Comparator<Expense> preference = Comparator
                .comparing((Expense e) -> proposed == null || e.getCategory() != proposed)
                .thenComparingLong(e -> Math.abs(e.getExpenseDate().toEpochDay() - date.toEpochDay()));

        List<Expense> match = near.stream()
                .filter(e -> e.getAmount().compareTo(total) == 0)
                .min(preference)
                .map(List::of)
                .orElseGet(() -> near.stream()
                        .collect(Collectors.groupingBy(Expense::getExpenseDate))
                        .values().stream()
                        .filter(group -> group.size() > 1)
                        .filter(group -> group.stream().map(Expense::getAmount)
                                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add).compareTo(total) == 0)
                        .min(Comparator.comparingLong(group ->
                                Math.abs(group.get(0).getExpenseDate().toEpochDay() - date.toEpochDay())))
                        .orElse(List.of()));
        if (match.isEmpty()) return false;

        Expense first = match.get(0);
        clearProposal(line);
        match.forEach(e -> ctx.claimedExpenses().add(e.getId()));
        line.setAction(BankLineAction.EXPENSE);
        line.setExpenseCategory(first.getCategory());
        line.setStorageUnit(first.getStorageUnit());
        line.setExpenseId(first.getId());
        line.setAlreadyRecorded(true);
        line.setStatus(BankLineStatus.DISCARDED);
        String what = match.size() == 1
                ? "el gasto de " + categoryText(first.getCategory()) + " del " + Pdfs.day(first.getExpenseDate())
                    + (first.getStorageUnit() == null ? "" : " (" + first.getStorageUnit().getName() + ")")
                : match.size() + " gastos del " + Pdfs.day(first.getExpenseDate()) + " que suman el cargo";
        line.setReason(truncate("Ya registrado a mano: " + what + ". Se ignora para no contarlo dos veces"));
        return true;
    }

    // ------------------------------------------------------------- Meses

    private static YearMonth periodOf(Payment p) {
        return p.getBillingPeriodYear() == null || p.getBillingPeriodMonth() == null
                ? null : YearMonth.of(p.getBillingPeriodYear(), p.getBillingPeriodMonth());
    }

    private static Map<YearMonth, Payment> byPeriod(List<Payment> payments) {
        Map<YearMonth, Payment> byPeriod = new HashMap<>();
        for (Payment p : payments) {
            YearMonth ym = periodOf(p);
            if (ym != null) byPeriod.put(ym, p);
        }
        return byPeriod;
    }

    /**
     * Los cobros de {@code count} meses seguidos, todos cobrados y sin casar con
     * otra fila, que incluyen el mes del movimiento o uno anterior y terminan
     * después de él. Con un solo mes no vale: pagar el mes siguiente unos días
     * antes es normal, y no se distingue de un cobro repetido.
     */
    private static List<Payment> settledRunReachingFuture(List<Payment> paid, YearMonth current, int count, Context ctx) {
        if (count < 2) return List.of();
        Map<YearMonth, Payment> byPeriod = byPeriod(paid);
        for (YearMonth start = current.minusMonths(count - 2); !start.isAfter(current); start = start.plusMonths(1)) {
            List<Payment> run = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Payment p = byPeriod.get(start.plusMonths(i));
                if (!isSettled(p) || ctx.claimedPayments().contains(p.getId())) break;
                run.add(p);
            }
            if (run.size() == count) return run;
        }
        return List.of();
    }

    /** Cobrada entera: lo pagado llega a lo debido. */
    private static boolean isSettled(Payment p) {
        return p != null && p.getStatus() != PaymentStatus.CANCELLED
                && p.getAmountPaid() != null && p.getAmountDue() != null
                && p.getAmountPaid().compareTo(p.getAmountDue()) >= 0;
    }

    /** Un mes en el que ya no cabe cobrar nada: cobrado entero o marcado como no cobrable. */
    private static boolean isClosed(Payment p) {
        return p != null && (p.getStatus() == PaymentStatus.CANCELLED || isSettled(p));
    }

    /** "julio de 2026" */
    private static String monthText(YearMonth ym) {
        return Pdfs.monthOf(ym.getYear(), ym.getMonthValue()).toLowerCase();
    }

    /** "julio de 2026", "julio y agosto de 2026", "noviembre, diciembre de 2026 y enero de 2027". */
    public static String monthsText(List<YearMonth> months) {
        if (months.isEmpty()) return "?";
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < months.size(); i++) {
            YearMonth ym = months.get(i);
            boolean sameYearAsNext = i + 1 < months.size() && months.get(i + 1).getYear() == ym.getYear();
            String text = monthText(ym);
            parts.add(sameYearAsNext ? text.substring(0, text.lastIndexOf(" de ")) : text);
        }
        if (parts.size() == 1) return parts.get(0);
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " y " + parts.get(parts.size() - 1);
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String categoryText(ExpenseCategory category) {
        return category == null ? "otros" : category.name().toLowerCase().replace('_', ' ');
    }

    /**
     * La mensualidad más antigua sin cobrar de los últimos meses. Null si están
     * todas cobradas hasta el mes del movimiento: entonces no se sabe si es un
     * pago adelantado o el mismo cobro otra vez, y no se adivina.
     */
    private static YearMonth firstOpenPeriod(RentalAgreement rental, LocalDate date, List<Payment> paid) {
        Map<YearMonth, Payment> byPeriod = byPeriod(paid);
        YearMonth current = YearMonth.from(date);
        YearMonth from = current.minusMonths(MONTHS_BACK);
        if (rental.getStartDate() != null && YearMonth.from(rental.getStartDate()).isAfter(from)) {
            from = YearMonth.from(rental.getStartDate());
        }
        YearMonth until = rental.getEndDate() != null && YearMonth.from(rental.getEndDate()).isBefore(current)
                ? YearMonth.from(rental.getEndDate()) : current;
        // Paga antes de entrar: el primer mes del contrato.
        if (from.isAfter(until)) return isClosed(byPeriod.get(from)) ? null : from;
        for (YearMonth ym = from; !ym.isAfter(until); ym = ym.plusMonths(1)) {
            if (!isClosed(byPeriod.get(ym))) return ym;
        }
        return null;
    }

    // ------------------------------------------------------------- Gastos

    // Las palabras que delatan la categoría de un cargo están en el vocabulario
    // (Vocabulary.expenseWords), en el orden en que se prueban: las comisiones
    // del banco primero, porque "COMISION MANTENIMIENTO" no es una reparación.

    private void proposeExpense(BankImportLine line, BankImportProfile profile, Context ctx) {
        String concept = TextMatch.normalize(line.getConcept());
        line.setAction(BankLineAction.EXPENSE);
        line.setStorageUnit(profile.getDefaultUnit());

        // Una transferencia a uno de los propietarios es un reparto, no un gasto.
        // Con su nombre completo, o con el de pila si el concepto dice que es un
        // traspaso o un reparto: "TRASPASO MENSUAL XIAO".
        Set<String> words = TextMatch.words(line.getConcept());
        boolean transferToOwner = TextMatch.containsAny(concept, TextMatch.vocabulary().ownerTransfer());
        for (Ownership share : ctx.ownerships()) {
            Owner owner = share.getOwner();
            if (owner == null || owner.isEntity()) continue;
            List<String> nameWords = TextMatch.nameWords(owner.getFullName());
            boolean byFirstName = transferToOwner && !nameWords.isEmpty() && words.contains(nameWords.get(0));
            if (TextMatch.nameScore(owner.getFullName(), words) > 0 || byFirstName) {
                line.setExpenseCategory(ExpenseCategory.REPARTO_BENEFICIOS);
                line.setReason("Transferencia a " + owner.getFullName() + ", propietario: reparto de beneficios");
                return;
            }
        }

        for (Map.Entry<ExpenseCategory, List<String>> entry : TextMatch.vocabulary().expenseWords().entrySet()) {
            for (String needle : entry.getValue()) {
                if (TextMatch.containsAny(concept, List.of(needle))) {
                    line.setExpenseCategory(entry.getKey());
                    line.setReason("Gasto de " + entry.getKey().name().toLowerCase().replace('_', ' ')
                            + " por «" + needle.toLowerCase() + "» en el concepto"
                            + (line.getStorageUnit() == null ? ". Falta la unidad" : ""));
                    return;
                }
            }
        }
        // Lo que no se reconoce no se propone como gasto: en una cuenta que también
        // se usa para lo personal (la compra, una cena, una nómina) serían la
        // mayoría de las filas, y habría que descartarlas una a una. Se deja en
        // "nada", a la vista; si es un gasto del alquiler, se elige y se aprende.
        line.setAction(BankLineAction.NONE);
        line.setStorageUnit(null);
        line.setStatus(BankLineStatus.DISCARDED);
        line.setReason("Cargo sin reconocer: si es un gasto del alquiler, recupéralo y elige la categoría y la unidad");
    }

    // ------------------------------------------------------------- Comunidad

    private void proposeCommunity(BankImportLine line, Set<String> words, Context ctx) {
        line.setAction(BankLineAction.COMMUNITY_ENTRY);
        if (!line.isIncome()) {
            line.setCommunityEntryType(CommunityEntryType.GASTO);
            line.setReason("Cargo en la cuenta de la comunidad: gasto del edificio");
            return;
        }
        line.setCommunityEntryType(CommunityEntryType.CUOTA);
        // La cuota la paga un propietario: se busca de qué piso es.
        Map<StorageUnit, String> candidates = new LinkedHashMap<>();
        for (Ownership share : ctx.ownerships()) {
            Owner owner = share.getOwner();
            if (owner == null || share.getStorageUnit() == null) continue;
            if (TextMatch.nameScore(owner.getFullName(), words) > 0) {
                candidates.putIfAbsent(share.getStorageUnit().rootUnit(), owner.getFullName());
            }
        }
        if (candidates.size() == 1) {
            Map.Entry<StorageUnit, String> only = candidates.entrySet().iterator().next();
            line.setStorageUnit(only.getKey());
            line.setReason("Cuota de " + only.getValue() + " (" + only.getKey().getName() + ")");
        } else {
            line.setReason(candidates.isEmpty()
                    ? "Ingreso en la cuenta de la comunidad: elige de qué unidad es la cuota"
                    : "Ingreso de un propietario con varias unidades (" + candidates.values().iterator().next()
                        + "): elige cuál, o repártelo entre ellas si paga la cuota de varias");
        }
    }

    // ------------------------------------------------------------- Apoyo

    private static void clearProposal(BankImportLine line) {
        line.setAction(BankLineAction.NONE);
        line.setRentalAgreement(null);
        line.setPeriodYear(null);
        line.setPeriodMonth(null);
        line.setPeriodCount(1);
        line.setExpenseCategory(null);
        line.setStorageUnit(null);
        line.getSplits().clear();
        line.setCommunityEntryType(null);
        line.setReason(null);
        line.setPaymentId(null);
        line.setAlreadyRecorded(false);
        if (!Boolean.TRUE.equals(line.getDuplicate())) line.setStatus(BankLineStatus.PENDING);
    }

    /** Arrendatarios y fiadores: cualquiera de ellos puede ser quien transfiere. */
    private static List<Client> signers(RentalAgreement rental) {
        List<Client> people = new ArrayList<>();
        if (rental.getParties() != null) {
            rental.getParties().stream().map(RentalParty::getClient).filter(Objects::nonNull).forEach(people::add);
        }
        if (people.isEmpty()) {
            people.addAll(rental.tenants());
            people.addAll(rental.guarantors());
        }
        return people;
    }

    /**
     * Si el contrato estaba vigente cerca de esa fecha. Con un margen: un
     * inquilino que se fue a final de mes puede pagar su último recibo días
     * después, y uno nuevo paga a veces antes de entrar.
     */
    private static boolean inForceAround(RentalAgreement rental, LocalDate date) {
        if (rental.getStartDate() != null && rental.getStartDate().isAfter(date.plusDays(15))) return false;
        return rental.getEndDate() == null || !rental.getEndDate().isBefore(date.minusDays(45));
    }

    /**
     * Reparte el cargo con las proporciones de una regla: cada unidad su parte,
     * redondeada al céntimo, y la última lo que quede, para que sumen justo el
     * cargo y no se pierda ni se invente un céntimo.
     */
    public static void splitByShares(BankImportLine line, List<BankMatchRuleSplit> shares) {
        line.setStorageUnit(null);
        line.getSplits().clear();
        java.math.BigDecimal total = line.getAmount().abs();
        java.math.BigDecimal left = total;
        for (int i = 0; i < shares.size(); i++) {
            BankMatchRuleSplit share = shares.get(i);
            java.math.BigDecimal part = i == shares.size() - 1
                    ? left
                    : total.multiply(share.getShare()).setScale(2, java.math.RoundingMode.HALF_UP);
            left = left.subtract(part);
            line.getSplits().add(BankImportLineSplit.builder()
                    .line(line).storageUnit(share.getStorageUnit()).amount(part).position(i).build());
        }
    }

    /** Si el contrato está en vigor ese mismo día, sin margen. */
    private static boolean inForceOn(RentalAgreement rental, LocalDate date) {
        if (rental.getStartDate() != null && rental.getStartDate().isAfter(date)) return false;
        return rental.getEndDate() == null || !rental.getEndDate().isBefore(date);
    }

    /** Si la unidad es esa o cuelga de ella (un trastero del bajo delantero). */
    private static boolean isUnder(StorageUnit unit, StorageUnit ancestor) {
        int guard = 0;
        for (StorageUnit u = unit; u != null && guard++ < 32; u = u.getParent()) {
            if (Objects.equals(u.getId(), ancestor.getId())) return true;
        }
        return false;
    }

    /** "trastero", "piso" o "local", para escribir el motivo como lo diría una persona. */
    private static String unitWordOf(RentalAgreement rental) {
        StorageUnit unit = rental.getStorageUnit();
        if (unit == null || unit.getKind() == null) return "unidad";
        return switch (unit.getKind()) {
            case STORAGE_UNIT -> "trastero";
            case APARTMENT -> "piso";
            case PREMISES -> "local";
        };
    }

    private static RentalAgreement rentalOfClient(Client client, LocalDate date, Context ctx) {
        return ctx.rentals().stream()
                .filter(r -> inForceAround(r, date))
                .filter(r -> signers(r).stream().anyMatch(c -> Objects.equals(c.getId(), client.getId())))
                .max(Comparator.comparing(RentalAgreement::getStartDate, Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
    }

    private static boolean sameAmount(RentalAgreement rental, BankImportLine line) {
        return rental != null && rental.getMonthlyCharge().compareTo(line.getAmount()) == 0;
    }

    private static String truncate(String text) {
        return text.length() > 300 ? text.substring(0, 297) + "..." : text;
    }

    /**
     * El cliente que se guarda en una regla de cobro: el titular del contrato.
     * Una regla recuerda a quién se le cobra, no el contrato, que cambia.
     */
    public static Client payerOf(RentalAgreement rental) {
        return rental.tenants().stream().findFirst().orElse(rental.getClient());
    }
}
