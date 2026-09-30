package com.storagemanager.storage_management;

import com.storagemanager.storage_management.config.InvoicingProperties;
import com.storagemanager.storage_management.model.Invoice;
import com.storagemanager.storage_management.model.Payment;
import com.storagemanager.storage_management.model.enums.InvoiceType;
import com.storagemanager.storage_management.repository.InvoiceRepository;
import com.storagemanager.storage_management.repository.PaymentRepository;
import com.storagemanager.storage_management.service.InvoiceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * La serie de facturas: correlativa, sin números repetidos, con las
 * rectificativas en su propia serie. Es lo que pide el reglamento de
 * facturación y lo que no se puede arreglar después: un número entregado no se
 * recupera.
 * <p>
 * Las facturas emitidas aquí se quedan en la base de la prueba, porque una
 * factura no se borra nunca; por eso cada prueba mira los números relativos a
 * los que ella misma emite y no da por hecho que la serie empieza en 1.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class InvoiceNumberingTest {

    @Autowired private InvoiceService invoices;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private PaymentRepository payments;
    @Autowired private InvoicingProperties properties;

    /** Cobros de trasteros con contrato y todavía sin factura: se pueden facturar. */
    private List<Payment> invoiceableCharges(int howMany) {
        List<Payment> candidates = payments.findAll().stream()
                .filter(p -> p.getRentalAgreement() != null)
                .filter(p -> p.getStorageUnit() != null && p.getStorageUnit().isVatApplicable())
                .filter(p -> p.getInvoiceNumber() == null)
                .filter(p -> p.getAmountDue() != null && p.getAmountDue().signum() > 0)
                .limit(howMany)
                .toList();
        assertEquals(howMany, candidates.size(), "no hay bastantes cobros sin facturar en los datos sembrados");
        return candidates;
    }

    private String prefix(String series) {
        return series + LocalDate.now().getYear() + "/";
    }

    private int ordinalOf(String number, String prefix) {
        assertTrue(number.startsWith(prefix), number + " no es de la serie " + prefix);
        assertEquals(prefix.length() + 4, number.length(), number + " no lleva el número con cuatro cifras");
        return Integer.parseInt(number.substring(prefix.length()));
    }

    @Test
    void consecutiveInvoicesGetConsecutiveNumbers() {
        List<Payment> charges = invoiceableCharges(2);
        String prefix = prefix(properties.getInvoiceSeries());

        Invoice first = invoices.issue(charges.get(0).getId());
        Invoice second = invoices.issue(charges.get(1).getId());

        assertEquals(InvoiceType.ORDINARIA, first.getType());
        assertEquals(ordinalOf(first.getNumber(), prefix) + 1, ordinalOf(second.getNumber(), prefix),
                "entre " + first.getNumber() + " y " + second.getNumber() + " hay un hueco");
    }

    @Test
    void askingTwiceForTheSameInvoiceDoesNotSpendANumber() {
        Payment charge = invoiceableCharges(1).get(0);
        Invoice first = invoices.issue(charge.getId());
        Invoice again = invoices.issue(charge.getId());
        assertEquals(first.getNumber(), again.getNumber(), "volver a pedirla gastó otro número de la serie");
        assertEquals(1, invoiceRepository.findByPaymentIdOrderByIssuedOnDescIdDesc(charge.getId()).size());
    }

    @Test
    void aCorrectionGoesInItsOwnSeriesAndPointsToTheOriginal() {
        assertNotEquals(properties.getInvoiceSeries(), properties.getRectificativeSeries(),
                "las rectificativas tienen que ir en una serie distinta de la ordinaria");
        Payment charge = invoiceableCharges(1).get(0);
        Invoice original = invoices.issue(charge.getId());

        Invoice correction = invoices.rectify(charge.getId(), "Importe mal cobrado");

        ordinalOf(correction.getNumber(), prefix(properties.getRectificativeSeries()));
        assertEquals(InvoiceType.RECTIFICATIVA, correction.getType());
        assertEquals(original.getId(), correction.getRectifies().getId());
        assertEquals("Importe mal cobrado", correction.getReason());
        // La que manda ahora es la rectificativa, y la original sigue ahí: no se borra.
        assertEquals(correction.getNumber(), payments.findById(charge.getId()).orElseThrow().getInvoiceNumber());
        assertTrue(invoiceRepository.findById(original.getId()).isPresent());
    }

    @Test
    void aCorrectionNeedsAReason() {
        Payment charge = invoiceableCharges(1).get(0);
        invoices.issue(charge.getId());
        assertThrows(RuntimeException.class, () -> invoices.rectify(charge.getId(), "  "));
    }

    /**
     * Dos cobros facturados a la vez nunca pueden llevar el mismo número. Si
     * coinciden en el tiempo, uno puede fallar -el número es único en la base- y
     * se reintenta; lo que no puede pasar es que los dos salgan con el mismo.
     */
    @Test
    void simultaneousInvoicesNeverShareANumber() throws InterruptedException {
        List<Payment> charges = invoiceableCharges(4);
        List<String> issued = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(charges.size());
        try {
            for (Payment charge : charges) {
                pool.submit(() -> {
                    try {
                        start.await();
                        issued.add(invoices.issue(charge.getId()).getNumber());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (RuntimeException collision) {
                        // Chocó con otra: el número es único y la base lo impide.
                    }
                });
            }
            start.countDown();
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "las facturas no terminaron de emitirse");
        }

        assertFalse(issued.isEmpty(), "no se emitió ninguna");
        assertEquals(issued.size(), new HashSet<>(issued).size(), "dos facturas con el mismo número: " + issued);

        Set<String> all = new HashSet<>();
        for (Invoice invoice : invoiceRepository.findAll()) {
            assertTrue(all.add(invoice.getNumber()), "número repetido en la serie: " + invoice.getNumber());
        }
    }
}
