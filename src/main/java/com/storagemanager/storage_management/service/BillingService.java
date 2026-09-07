package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.dto.MonthlyChargeDTO;
import com.storagemanager.storage_management.model.Payment;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.enums.PaymentStatus;
import com.storagemanager.storage_management.model.enums.RentalStatus;
import com.storagemanager.storage_management.repository.PaymentRepository;
import com.storagemanager.storage_management.repository.RentalAgreementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lo que se debe, mes a mes, según los contratos.
 * <p>
 * Aquí no se emiten recibos por adelantado: un {@link Payment} es siempre un
 * cobro recibido. Lo que queda por cobrar se deduce de los contratos, un cargo
 * ({@link MonthlyChargeDTO}) por cada mes en vigor de cada contrato, cruzado con
 * los cobros de ese periodo. Es la única fuente de "esperado", "pendiente" y
 * "vencido" de toda la aplicación: el panel, las estadísticas y la vista de
 * mensualidades leen de aquí.
 * <p>
 * Un mes cuenta como <em>vencido</em> en cuanto el mes termina sin cobro; el mes
 * en curso sólo está <em>pendiente</em>. Un mes que se decide a mano que no se
 * cobra (su cobro queda anulado) pasa a <em>no cobrable</em> y sale de todas las
 * cuentas: ni se espera, ni se debe, ni vence.
 */
@Service
@RequiredArgsConstructor
public class BillingService {

    public static final String COLLECTED = "COLLECTED";
    public static final String PENDING = "PENDING";
    public static final String OVERDUE = "OVERDUE";
    /** Mes que se ha decidido a mano que no se cobra: ni se espera ni se debe. */
    public static final String WAIVED = "WAIVED";

    private final RentalAgreementRepository rentalAgreementRepository;
    private final PaymentRepository paymentRepository;

    /** Todos los cargos, desde el primer mes de cada contrato hasta el mes en curso. */
    public List<MonthlyChargeDTO> allCharges() {
        return charges(null, null);
    }

    /** Los cargos de un solo mes. */
    public List<MonthlyChargeDTO> chargesOf(YearMonth month) {
        return charges(month, month);
    }

    /**
     * Los cargos cuyo periodo cae dentro de [from, to]; un extremo nulo no limita
     * por ese lado. Nunca se generan meses futuros: un mes que aún no ha llegado
     * todavía no se debe.
     */
    public List<MonthlyChargeDTO> charges(YearMonth from, YearMonth to) {
        YearMonth currentMonth = YearMonth.now();
        YearMonth lastGenerated = (to == null || to.isAfter(currentMonth)) ? currentMonth : to;

        Map<String, Payment> cobros = new HashMap<>();
        for (Payment p : paymentRepository.findAll()) {
            if (p.getRentalAgreement() == null || p.getBillingPeriodYear() == null || p.getBillingPeriodMonth() == null) {
                continue;
            }
            cobros.put(key(p.getRentalAgreement().getId(), p.getBillingPeriodYear(), p.getBillingPeriodMonth()), p);
        }

        List<MonthlyChargeDTO> charges = new ArrayList<>();
        Set<String> generated = new HashSet<>();

        for (RentalAgreement rental : rentalAgreementRepository.findAll()) {
            if (rental.getStartDate() == null) continue;
            YearMonth start = YearMonth.from(rental.getStartDate());
            YearMonth end = lastMonthInForce(rental, lastGenerated);
            if (from != null && start.isBefore(from)) start = from;

            for (YearMonth ym = start; !ym.isAfter(end); ym = ym.plusMonths(1)) {
                String k = key(rental.getId(), ym.getYear(), ym.getMonthValue());
                generated.add(k);
                charges.add(charge(rental, ym, cobros.get(k), currentMonth));
            }
        }

        // Cobros de un periodo que ningún contrato cubre (registrados fuera de sus
        // fechas): se listan igualmente para que ningún ingreso quede escondido.
        for (Map.Entry<String, Payment> entry : cobros.entrySet()) {
            if (generated.contains(entry.getKey())) continue;
            Payment p = entry.getValue();
            YearMonth ym = YearMonth.of(p.getBillingPeriodYear(), p.getBillingPeriodMonth());
            if (from != null && ym.isBefore(from)) continue;
            if (to != null && ym.isAfter(to)) continue;
            charges.add(charge(p.getRentalAgreement(), ym, p, currentMonth));
        }

        charges.sort(Comparator
                .comparing(MonthlyChargeDTO::getBillingPeriodYear)
                .thenComparing(MonthlyChargeDTO::getBillingPeriodMonth)
                .thenComparing(c -> unitNumberOf(c), Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return charges;
    }

    /**
     * Último mes que un contrato debe, nunca más allá de {@code cap}. Un contrato
     * terminado deja de deber tras su fecha de fin; si le falta (sólo posible
     * editándolo a mano) se le da por terminado en su mes de inicio, antes que
     * cobrarle meses de más.
     */
    private static YearMonth lastMonthInForce(RentalAgreement rental, YearMonth cap) {
        YearMonth end;
        if (rental.getEndDate() != null) {
            end = YearMonth.from(rental.getEndDate());
        } else if (rental.getStatus() == RentalStatus.TERMINATED) {
            end = YearMonth.from(rental.getStartDate());
        } else {
            end = cap;
        }
        return end.isAfter(cap) ? cap : end;
    }

    private static MonthlyChargeDTO charge(RentalAgreement rental, YearMonth ym, Payment payment, YearMonth currentMonth) {
        BigDecimal due = payment != null && payment.getAmountDue() != null
                ? payment.getAmountDue()
                : rental.getMonthlyRent();
        if (due == null) due = BigDecimal.ZERO;
        BigDecimal paid = payment != null && payment.getAmountPaid() != null ? payment.getAmountPaid() : BigDecimal.ZERO;

        // Un cobro anulado marca el mes como no cobrable: se deja a cero para que no
        // salga en vencidos, ni en lo esperado, ni en lo que falta por cobrar.
        boolean waived = payment != null && payment.getStatus() == PaymentStatus.CANCELLED;
        if (waived) due = BigDecimal.ZERO;
        BigDecimal outstanding = waived ? BigDecimal.ZERO : due.subtract(paid).max(BigDecimal.ZERO);

        String status = waived
                ? WAIVED
                : outstanding.signum() <= 0
                        ? COLLECTED
                        : ym.isBefore(currentMonth) ? OVERDUE : PENDING;

        return MonthlyChargeDTO.builder()
                .id(key(rental.getId(), ym.getYear(), ym.getMonthValue()))
                .rentalAgreementId(rental.getId())
                .agreementNumber(rental.getAgreementNumber())
                .storageUnit(payment != null && payment.getStorageUnit() != null
                        ? payment.getStorageUnit() : rental.getStorageUnit())
                .client(payment != null && payment.getClient() != null ? payment.getClient() : rental.getClient())
                .billingPeriodYear(ym.getYear())
                .billingPeriodMonth(ym.getMonthValue())
                .dueDate(payment != null && payment.getDueDate() != null ? payment.getDueDate() : dueDate(rental, ym))
                .amountDue(due)
                .amountPaid(paid)
                .outstanding(outstanding)
                .status(status)
                .payment(payment)
                .build();
    }

    /** El día de cobro pactado, recortado al último día del mes (febrero, día 31...). */
    private static LocalDate dueDate(RentalAgreement rental, YearMonth ym) {
        int day = rental.getBillingDayOfMonth() != null ? rental.getBillingDayOfMonth() : 1;
        return ym.atDay(Math.min(Math.max(day, 1), ym.lengthOfMonth()));
    }

    private static String unitNumberOf(MonthlyChargeDTO charge) {
        return charge.getStorageUnit() != null ? charge.getStorageUnit().getUnitNumber() : null;
    }

    private static String key(Long rentalId, int year, int month) {
        return rentalId + "-" + year + "-" + month;
    }
}
