package com.storagemanager.storage_management.service.bank;

import com.storagemanager.storage_management.model.BankImportLine;
import com.storagemanager.storage_management.model.BankImportProfile;
import com.storagemanager.storage_management.model.BankImportLineSplit;
import com.storagemanager.storage_management.model.BankMatchRule;
import com.storagemanager.storage_management.model.BankMatchRuleSplit;
import com.storagemanager.storage_management.model.Client;
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
    /** Hasta cuántos meses atrás se busca una mensualidad sin cobrar. */
    private static final int MONTHS_BACK = 6;

    private final RentalAgreementRepository rentals;
    private final PaymentRepository payments;
    private final BankMatchRuleRepository rules;
    private final OwnershipRepository ownerships;

    /**
     * Lo que hace falta para proponer, cargado una vez por extracto.
     *
     * @param claimedPayments los cobros hechos a mano que ya se han dado por
     *                        "esta fila es este cobro": uno no puede tapar dos filas
     */
    public record Context(List<RentalAgreement> rentals, Map<Long, List<Payment>> paymentsByRental,
                         List<Payment> paidPayments, List<BankMatchRule> rules, List<Ownership> ownerships,
                         Set<Long> claimedPayments) {}

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
        return new Context(rentals.findAll(), byRental, paid, sorted, ownerships.findAll(), new java.util.HashSet<>());
    }

    /** Deja en la línea la propuesta y su motivo. */
    public void propose(BankImportLine line, BankImportProfile profile, Context ctx) {
        clearProposal(line);
        Set<String> words = TextMatch.words(line.getConcept());

        for (BankMatchRule rule : ctx.rules()) {
            if (TextMatch.matchesRule(rule.getPattern(), words) && applyRule(line, rule, profile, ctx)) return;
        }

        // La cuota de un préstamo no es un gasto entero: si financia los pisos, sus
        // intereses sí son deducibles en el IRPF del alquiler, pero la amortización
        // de capital no, y el extracto solo trae la cuota. Se deja a la vista, sin
        // proponer nada, para que se apunten los intereses del cuadro de amortización.
        if (!line.isIncome() && TextMatch.containsAny(TextMatch.normalize(line.getConcept()),
                List.of("PRESTAMO", "HIPOTECA", "HIPOTECARIO"))) {
            line.setAction(BankLineAction.NONE);
            line.setReason("Cuota de un préstamo: no crea nada, porque mezcla intereses y capital. Una vez al año, apunta en Gastos "
                    + "los intereses del certificado del banco (categoría «Intereses de financiación», en su unidad). "
                    + "Si la marcas como «Nada» y la recuerdas, los meses siguientes saldrá ignorada");
            return;
        }

        // Una fianza no es una mensualidad ni un gasto: es dinero del inquilino
        // que se le devuelve. Se lleva en la pestaña de la fianza del contrato.
        if (words.contains("FIANZA") || words.contains("FIANZAS")) {
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
                    markRecorded(line, recorded, ctx, " (por una regla aprendida)");
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

        // Por el importe, si solo hay un contrato en vigor que cobre eso.
        List<RentalAgreement> byAmount = ctx.rentals().stream()
                .filter(r -> inForceAround(r, date) && sameAmount(r, line))
                .toList();
        if (byAmount.size() == 1) {
            proposePayment(line, byAmount.get(0), "Por el importe (no sale ningún nombre conocido): compruébalo", ctx);
            return;
        }

        line.setAction(BankLineAction.NONE);
        if (byAmount.isEmpty()) {
            // Ni nombre, ni unidad, ni un contrato que cobre eso: casi siempre algo
            // personal (una nómina, una devolución). Se ignora, a la vista en
            // «Ignoradas» por si acaso.
            line.setStatus(BankLineStatus.DISCARDED);
            line.setReason("Ingreso sin reconocer: si es un cobro del alquiler, recupéralo y elige el contrato");
        } else {
            line.setReason(byAmount.size() + " contratos cobran " + Pdfs.euros(line.getAmount()) + ": elige cuál es");
        }
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
     */
    private void proposePayment(BankImportLine line, RentalAgreement rental, String why, Context ctx) {
        List<Payment> paid = ctx.paymentsByRental().getOrDefault(rental.getId(), List.of());
        line.setAction(BankLineAction.RENT_PAYMENT);
        line.setRentalAgreement(rental);

        int count = monthsCovered(rental, line);
        YearMonth named = namedPeriod(line);
        YearMonth period = named != null ? named : firstOpenPeriod(rental, line.getDate(), paid);
        line.setPeriodYear(period.getYear());
        line.setPeriodMonth(period.getMonthValue());
        line.setPeriodCount(count);

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
        if (recorded == null) return false;
        markRecorded(line, recorded, ctx, "");
        return true;
    }

    /**
     * Segunda pasada: un ingreso sin nombre reconocible que coincide en importe
     * y fecha con un único cobro apuntado a mano y todavía libre. Se da por ese,
     * avisando de que hay que comprobarlo; si hay varios posibles, no se elige.
     */
    private boolean markRecordedByAmount(BankImportLine line, Context ctx) {
        List<Payment> candidates = recordedCandidates(line, ctx);
        if (candidates.size() != 1) return false;
        markRecorded(line, candidates.get(0), ctx, " (por importe y fecha: compruébalo)");
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

    private static void markRecorded(BankImportLine line, Payment payment, Context ctx, String note) {
        clearProposal(line);
        ctx.claimedPayments().add(payment.getId());
        line.setAction(BankLineAction.RENT_PAYMENT);
        line.setRentalAgreement(payment.getRentalAgreement());
        line.setPeriodYear(payment.getBillingPeriodYear());
        line.setPeriodMonth(payment.getBillingPeriodMonth());
        line.setPaymentId(payment.getId());
        line.setAlreadyRecorded(true);
        line.setStatus(BankLineStatus.DISCARDED);
        line.setReason(truncate("Ya registrado a mano: el cobro de "
                + Pdfs.monthOf(payment.getBillingPeriodYear(), payment.getBillingPeriodMonth()).toLowerCase()
                + " del " + Pdfs.day(payment.getPaymentDate()) + note + ". Se ignora para no contarlo dos veces"));
    }

    /** La mensualidad más antigua sin cobrar de los últimos meses; si no hay, la del movimiento. */
    private static YearMonth firstOpenPeriod(RentalAgreement rental, LocalDate date, List<Payment> paid) {
        Map<YearMonth, Payment> byPeriod = new HashMap<>();
        for (Payment p : paid) {
            if (p.getBillingPeriodYear() != null && p.getBillingPeriodMonth() != null) {
                byPeriod.put(YearMonth.of(p.getBillingPeriodYear(), p.getBillingPeriodMonth()), p);
            }
        }
        YearMonth current = YearMonth.from(date);
        YearMonth from = current.minusMonths(MONTHS_BACK);
        if (rental.getStartDate() != null && YearMonth.from(rental.getStartDate()).isAfter(from)) {
            from = YearMonth.from(rental.getStartDate());
        }
        YearMonth until = rental.getEndDate() != null && YearMonth.from(rental.getEndDate()).isBefore(current)
                ? YearMonth.from(rental.getEndDate()) : current;
        for (YearMonth ym = from; !ym.isAfter(until); ym = ym.plusMonths(1)) {
            Payment p = byPeriod.get(ym);
            boolean open = p == null
                    || (p.getStatus() != PaymentStatus.CANCELLED
                        && (p.getAmountPaid() == null || p.getAmountDue() == null
                            || p.getAmountPaid().compareTo(p.getAmountDue()) < 0));
            if (open) return ym;
        }
        return current;
    }

    // ------------------------------------------------------------- Gastos

    /**
     * Las palabras que delatan la categoría de un cargo. Las comisiones del banco
     * van primero: "COMISION MANTENIMIENTO" no es una reparación.
     */
    private static final List<Map.Entry<ExpenseCategory, List<String>>> CATEGORY_WORDS = List.of(
            Map.entry(ExpenseCategory.OTROS, List.of("COMISION", "COMISIONES", "MANTENIMIENTO CUENTA", "CUOTA TARJETA")),
            // "CARGO POR PAGO DE IMPUESTOS - TRIBUTOS NRC ...": el pago a la AEAT con
            // su NRC. Va antes que los tributos locales, que también dicen "TRIBUTOS".
            Map.entry(ExpenseCategory.IMPUESTOS, List.of("AEAT", "AGENCIA TRIBUTARIA", "AGENCIA ESTATAL", "HACIENDA",
                    "MODELO 303", "MOD 303", "IMPUESTO", "IMPUESTOS", "NRC")),
            // "TAXA OUTORGAMENTO DE LICENCIAS URBANISTICAS": en gallego, tasa es taxa.
            Map.entry(ExpenseCategory.TRIBUTOS, List.of("IBI", "AYUNTAMIENTO", "CONCELLO", "RECAUDACION", "TRIBUTOS",
                    "TASA", "TASAS", "TAXA", "TAXAS", "LICENCIA", "LICENCIAS", "BASURA", "DEPUTACION", "DIPUTACION")),
            Map.entry(ExpenseCategory.SUMINISTROS, List.of("IGNIS", "IBERDROLA", "ENDESA", "NATURGY", "REPSOL", "EDP", "HOLALUZ",
                    "TOTALENERGIES", "AQUALIA", "EMALCSA", "AUGAS", "AGUA", "LUZ", "ELECTRICIDAD", "TELEFONICA",
                    "MOVISTAR", "VODAFONE", "ORANGE", "DIGI", "R CABLE", "FIBRA")),
            Map.entry(ExpenseCategory.SEGUROS, List.of("SEGURO", "SEGUROS", "MAPFRE", "MUTUA", "ALLIANZ", "AXA", "GENERALI",
                    "LINEA DIRECTA", "OCASO", "SANTALUCIA", "REALE", "CASER", "ZURICH", "PELAYO")),
            Map.entry(ExpenseCategory.COMUNIDAD, List.of("COMUNIDAD", "CDAD", "COM PROP", "COMUNIDAD PROPIETARIOS")),
            Map.entry(ExpenseCategory.REPARACIONES, List.of("REPARACION", "FONTANERO", "FONTANERIA", "ELECTRICISTA",
                    "PINTURA", "CERRAJERO", "CERRAJERIA", "MANTENIMIENTO", "OBRA", "FERRETERIA", "LEROY")));

    private void proposeExpense(BankImportLine line, BankImportProfile profile, Context ctx) {
        String concept = TextMatch.normalize(line.getConcept());
        line.setAction(BankLineAction.EXPENSE);
        line.setStorageUnit(profile.getDefaultUnit());

        // Una transferencia a uno de los propietarios es un reparto, no un gasto.
        // Con su nombre completo, o con el de pila si el concepto dice que es un
        // traspaso o un reparto: "TRASPASO MENSUAL XIAO".
        Set<String> words = TextMatch.words(line.getConcept());
        boolean transferToOwner = words.contains("TRASPASO") || words.contains("REPARTO");
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

        for (Map.Entry<ExpenseCategory, List<String>> entry : CATEGORY_WORDS) {
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
