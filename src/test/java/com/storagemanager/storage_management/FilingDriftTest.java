package com.storagemanager.storage_management;

import com.storagemanager.storage_management.dto.FilingDriftDTO;
import com.storagemanager.storage_management.dto.Modelo303DTO;
import com.storagemanager.storage_management.model.Payment;
import com.storagemanager.storage_management.model.TaxFiling;
import com.storagemanager.storage_management.model.enums.PaymentStatus;
import com.storagemanager.storage_management.model.enums.TaxModel;
import com.storagemanager.storage_management.repository.PaymentRepository;
import com.storagemanager.storage_management.repository.TaxFilingRepository;
import com.storagemanager.storage_management.service.FilingDriftService;
import com.storagemanager.storage_management.service.TaxService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * La vigilancia de lo ya declarado: si se toca un cobro de un trimestre
 * presentado, la declaración deja de cuadrar y se dice; si no se toca nada,
 * no se dice nada.
 * <p>
 * Cada prueba registra su propia declaración y deshace lo que cambia: el
 * contexto de Spring se comparte con las demás clases de prueba, y las cifras
 * de los años cerrados las vigila {@code TaxControllerTest}.
 */
// Las mismas anotaciones que las demás pruebas de integración: así se reutiliza
// el contexto que ya tienen arrancado en vez de levantar otro.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class FilingDriftTest {

    /** Un año entero de datos sembrados, con cobros de trasteros en todos los trimestres. */
    private static final int YEAR = 2025;
    private static final int QUARTER = 2;

    @Autowired private FilingDriftService driftService;
    @Autowired private TaxService taxService;
    @Autowired private TaxFilingRepository filings;
    @Autowired private PaymentRepository payments;
    @Autowired private ObjectMapper objectMapper;

    /** Registra el 303 del trimestre tal como lo calcula hoy la aplicación. */
    private TaxFiling fileTodays303() {
        Modelo303DTO report = taxService.modelo303(YEAR, null);
        return filings.save(TaxFiling.builder()
                .model(TaxModel.MODELO_303)
                .year(YEAR)
                .quarter(QUARTER)
                .filedDate(LocalDate.of(YEAR, 7, 20))
                .snapshot(objectMapper.writeValueAsString(report))
                .notes("FilingDriftTest")
                .build());
    }

    private Optional<FilingDriftDTO> driftOf(TaxFiling filing) {
        return driftService.drifts().stream().filter(d -> d.filingId().equals(filing.getId())).findFirst();
    }

    /** Un cobro ya pagado del trimestre, de una unidad con IVA: de los que entran en el 303. */
    private Payment paidChargeOfTheQuarter() {
        List<Payment> candidates = payments.findByBillingPeriodYear(YEAR).stream()
                .filter(p -> p.getStatus() == PaymentStatus.PAID)
                .filter(p -> p.getBillingPeriodMonth() >= (QUARTER - 1) * 3 + 1 && p.getBillingPeriodMonth() <= QUARTER * 3)
                .filter(p -> p.getStorageUnit() != null && p.getStorageUnit().isVatApplicable())
                .filter(p -> p.getAmountPaid() != null && p.getAmountPaid().signum() > 0)
                .toList();
        assertFalse(candidates.isEmpty(), "no hay cobros de trasteros sembrados en el " + QUARTER + "T " + YEAR);
        return candidates.get(0);
    }

    @Test
    void aFilingThatStillMatchesRaisesNothing() {
        TaxFiling filing = fileTodays303();
        try {
            assertTrue(driftOf(filing).isEmpty(), "una declaración recién calculada no puede descuadrar");
        } finally {
            filings.delete(filing);
        }
    }

    @Test
    void touchingAChargeOfADeclaredQuarterIsReported() {
        TaxFiling filing = fileTodays303();
        Payment payment = paidChargeOfTheQuarter();
        BigDecimal original = payment.getAmountPaid();
        try {
            // 12,10 € más con IVA son 10,00 € más de base y 2,10 € de cuota.
            payment.setAmountPaid(original.add(new BigDecimal("12.10")));
            payments.save(payment);

            FilingDriftDTO drift = driftOf(filing).orElseThrow(
                    () -> new AssertionError("se cambió un cobro del trimestre presentado y no se avisó"));
            FilingDriftDTO.Change base = drift.changes().stream()
                    .filter(c -> c.field().equals("Base cobrada"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no sale el cambio de la base cobrada: " + drift.changes()));
            assertEquals(0, base.difference().compareTo(new BigDecimal("10.00")),
                    "la diferencia de base tendría que ser 10,00 € y es " + base.difference());
            assertEquals("Modelo 303 · 2T 2025", drift.label());
        } finally {
            payment.setAmountPaid(original);
            payments.save(payment);
            filings.delete(filing);
        }
        assertTrue(driftService.drifts().stream().noneMatch(d -> d.filingId().equals(filing.getId())));
    }

    @Test
    void anUnreadableSnapshotDoesNotStopTheCheck() {
        TaxFiling broken = filings.save(TaxFiling.builder()
                .model(TaxModel.MODELO_184)
                .year(YEAR)
                .filedDate(LocalDate.of(YEAR + 1, 1, 31))
                .snapshot("{esto no es un informe")
                .notes("FilingDriftTest")
                .build());
        try {
            List<FilingDriftDTO> drifts = assertDoesNotThrow(() -> driftService.drifts());
            assertTrue(drifts.stream().noneMatch(d -> d.filingId().equals(broken.getId())));
        } finally {
            filings.delete(broken);
        }
    }
}
