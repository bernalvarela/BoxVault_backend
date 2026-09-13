package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.dto.Modelo303DTO;
import com.storagemanager.storage_management.model.Expense;
import com.storagemanager.storage_management.model.TaxFiling;
import com.storagemanager.storage_management.model.enums.ExpenseCategory;
import com.storagemanager.storage_management.model.enums.TaxModel;
import com.storagemanager.storage_management.repository.ExpenseRepository;
import com.storagemanager.storage_management.repository.TaxFilingDocumentRepository;
import com.storagemanager.storage_management.repository.TaxFilingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reconstruye el registro de Modelos 303 presentados a partir de lo que de
 * verdad se pagó: los gastos de categoría {@link ExpenseCategory#IMPUESTOS} que
 * son un ingreso a la AEAT (llevan su NRC en la descripción).
 * <p>
 * Las cifras que calcula la aplicación son informativas y cambian según se
 * corrigen cobros y gastos; lo que se presentó, en cambio, es un hecho, y el
 * único rastro que hay de él es el cargo en cuenta. Así que el registro lo
 * mandan los pagos: cada uno es la declaración de su trimestre, con su fecha,
 * su importe y su NRC, y el {@code snapshot} guarda lo que la aplicación
 * calculaba en el momento de reconstruirlo, para poder comparar.
 * <p>
 * Esto se hace <b>una sola vez</b>, cuando el registro está vacío: lo lanza el
 * {@link com.storagemanager.storage_management.config.DataSeeder} al arrancar y no
 * hay forma de pedirlo desde la aplicación. Un registro de lo presentado no se
 * resincroniza: una vez es correcto, volver a generarlo desde unos datos que
 * siguen cambiando sólo puede estropearlo. Lo que sí se ve en cada momento es la
 * diferencia entre lo ingresado y lo que la aplicación calcula hoy, que la sirve
 * {@link TaxFilingService}; corregir una declaración concreta es borrarla y
 * registrarla a mano.
 * <p>
 * Lo único que respeta al reconstruir son las declaraciones con justificantes
 * archivados: ésas no se borran nunca, se avisa de ellas.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Modelo303RegisterService {

    /** Lo que marca un gasto como ingreso a la AEAT, además de la categoría IMPUESTOS. */
    private static final String AEAT_MARKER = "AEAT";

    /** El NRC (justificante del ingreso) tal como viene en la descripción del gasto. */
    private static final Pattern NRC = Pattern.compile("NRC\\s+([A-Za-z0-9]+)");

    private final ExpenseRepository expenseRepository;
    private final TaxFilingRepository taxFilingRepository;
    private final TaxFilingDocumentRepository filingDocumentRepository;
    private final TaxService taxService;
    private final ObjectMapper objectMapper;

    /**
     * Resultado de la reconstrucción.
     *
     * @param registered   declaraciones que quedan en el registro, una por pago
     * @param removed      las que se borraron por no corresponder a ningún pago
     * @param keptWithDocs las que no se borraron porque tienen justificantes archivados
     */
    public record Result(int registered, int removed, List<String> keptWithDocs) {}

    /** Un pago a la AEAT ya situado en su trimestre. */
    private record Payment(Expense expense, int year, int quarter) {
        String label() {
            return quarter + "T " + year;
        }
    }

    @Transactional
    public Result rebuildFromPayments() {
        List<Payment> payments = aeatPayments();
        Map<String, Payment> byPeriod = new HashMap<>();
        for (Payment p : payments) {
            // Si hubiera dos cargos del mismo trimestre (un complementario), manda el último.
            byPeriod.merge(p.label(), p, (a, b) ->
                    a.expense().getExpenseDate().isAfter(b.expense().getExpenseDate()) ? a : b);
        }

        List<TaxFiling> existing = taxFilingRepository.findAll().stream()
                .filter(f -> f.getModel() == TaxModel.MODELO_303)
                .toList();

        int removed = 0;
        List<String> keptWithDocs = new ArrayList<>();
        for (TaxFiling filing : existing) {
            String period = filing.getQuarter() + "T " + filing.getYear();
            if (byPeriod.containsKey(period)) continue;
            if (!filingDocumentRepository
                    .findByTaxFilingIdOrderByDocumentUploadedAtDescDocumentIdDesc(filing.getId()).isEmpty()) {
                // Tiene justificantes: alguien archivó ahí el acuse de la Sede, así
                // que la declaración existió aunque no encontremos su pago.
                keptWithDocs.add(period);
                continue;
            }
            taxFilingRepository.delete(filing);
            removed++;
        }

        // Un informe por año: la misma cuenta vale para los cuatro trimestres.
        Map<Integer, Modelo303DTO> reportsByYear = new HashMap<>();
        int registered = 0;
        for (Payment payment : byPeriod.values()) {
            Modelo303DTO report = reportsByYear.computeIfAbsent(payment.year(), y -> taxService.modelo303(y, null));
            TaxFiling filing = existing.stream()
                    .filter(f -> payment.year() == f.getYear() && Integer.valueOf(payment.quarter()).equals(f.getQuarter()))
                    .findFirst()
                    .orElseGet(TaxFiling::new);
            apply(filing, payment, report);
            taxFilingRepository.save(filing);
            registered++;
        }

        log.info("Registro del Modelo 303 reconstruido desde los pagos a la AEAT: {} declaración(es), {} borrada(s)",
                registered, removed);
        if (!keptWithDocs.isEmpty()) {
            log.warn("Sin pago a la AEAT que las respalde, pero conservadas por tener justificantes archivados: {}",
                    String.join(", ", keptWithDocs));
        }
        return new Result(registered, removed, keptWithDocs);
    }

    /** Los gastos que son un ingreso a la AEAT, con el trimestre que pagan. */
    private List<Payment> aeatPayments() {
        List<Payment> payments = new ArrayList<>();
        for (Expense e : expenseRepository.findByCategory(ExpenseCategory.IMPUESTOS)) {
            if (e.getExpenseDate() == null || e.getDescription() == null) continue;
            if (!e.getDescription().toUpperCase().contains(AEAT_MARKER)) continue;
            payments.add(new Payment(e, periodYear(e.getExpenseDate()), periodQuarter(e.getExpenseDate())));
        }
        return payments;
    }

    /**
     * El trimestre que paga un cargo hecho en esa fecha. El 303 se presenta el mes
     * siguiente al cierre del trimestre (enero para el 4T, y hasta el 30), así que
     * un cargo de enero o febrero paga el 4T del año anterior.
     */
    private static int periodQuarter(LocalDate date) {
        int month = date.getMonthValue();
        if (month <= 2 || month == 12) return 4;
        return (month - 3) / 3 + 1;
    }

    private static int periodYear(LocalDate date) {
        return date.getMonthValue() <= 2 ? date.getYear() - 1 : date.getYear();
    }

    private void apply(TaxFiling filing, Payment payment, Modelo303DTO report) {
        Expense expense = payment.expense();
        Modelo303DTO.Quarter q = report.getQuarters().get(payment.quarter() - 1);
        BigDecimal paid = money(expense.getAmount());

        filing.setModel(TaxModel.MODELO_303);
        filing.setYear(payment.year());
        filing.setQuarter(payment.quarter());
        filing.setFiledDate(expense.getExpenseDate());
        // El importe del registro es lo que se ingresó de verdad, no lo que calcula
        // la aplicación: el registro cuenta lo que pasó.
        filing.setAmount(paid);
        filing.setExpenseId(expense.getId());
        filing.setDescription("IVA " + payment.label() + ": ingresado " + paid.toPlainString() + " EUR"
                + nrcOf(expense).map(nrc -> " (NRC " + nrc + ")").orElse(""));
        filing.setSnapshot(toJson(report));
        filing.setNotes("Reconstruida desde el pago a la AEAT del " + expense.getExpenseDate()
                + " (gasto #" + expense.getId() + "). La aplicación calcula para este trimestre una cuota de "
                + money(q.getCollectedVat()).toPlainString() + " EUR sobre una base de "
                + money(q.getCollectedBase()).toPlainString() + " EUR.");
    }

    private static Optional<String> nrcOf(Expense expense) {
        Matcher matcher = NRC.matcher(expense.getDescription());
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2, RoundingMode.HALF_UP);
    }

    private String toJson(Object report) {
        try {
            return objectMapper.writeValueAsString(report);
        } catch (tools.jackson.core.JacksonException e) {
            log.warn("No se pudo serializar el informe del 303: {}", e.getMessage());
            return null;
        }
    }
}
