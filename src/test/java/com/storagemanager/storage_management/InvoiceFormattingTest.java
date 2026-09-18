package com.storagemanager.storage_management;

import com.storagemanager.storage_management.model.Payment;
import com.storagemanager.storage_management.service.pdf.Pdfs;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lo que sale impreso en una factura y lo que avisa de que hay que rectificar.
 * <p>
 * Los meses y los importes se comprueban aquí porque ya fallaron en producción:
 * la imagen nativa sólo lleva los datos del idioma con el que se construye, así
 * que pedirle al JDK el español devolvía "Sep de 2026" en el servidor mientras
 * en local salía "Septiembre de 2026". Ahora están escritos en el código, y esta
 * prueba lo sujeta cambiando el idioma por defecto a uno cualquiera.
 */
class InvoiceFormattingTest {

    @Test
    void amountsAndMonthsAreSpanishWhateverTheJvmLocaleIs() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);

            assertEquals("1.234,56 €", Pdfs.euros(new BigDecimal("1234.56")));
            assertEquals("55,00 €", Pdfs.euros(new BigDecimal("55")));
            assertEquals("0,00 €", Pdfs.euros(null));

            assertEquals("Septiembre de 2026", Pdfs.monthOf(2026, 9));
            assertEquals("Enero de 2024", Pdfs.monthOf(2024, 1));
            assertEquals("17 de septiembre de 2026", Pdfs.longDay(LocalDate.of(2026, 9, 17)));
            assertEquals("17/09/2026", Pdfs.day(LocalDate.of(2026, 9, 17)));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void fileNamesSayWhatTheyAre() {
        assertEquals("trastero-3", Pdfs.slug("Trastero 3"));
        assertEquals("bajo-delantero", Pdfs.slug("Bajo delantero"));
        // Sin tildes ni eñes: un nombre de fichero viaja por sistemas que no las llevan bien.
        assertEquals("almacen-n-2", Pdfs.slug("Almacén nº 2"));
        assertEquals("", Pdfs.slug(null));
    }

    @Test
    void aChangedChargeNoLongerMatchesItsInvoice() {
        Payment payment = Payment.builder()
                .amountDue(new BigDecimal("55.00"))
                .invoiceNumber("A2026/0001")
                .invoicedTotal(new BigDecimal("55.00"))
                .build();
        assertFalse(payment.isInvoiceOutdated(), "lo facturado coincide con la mensualidad");

        // El mes sube de precio después de facturarlo: hay que rectificar.
        payment.setAmountDue(new BigDecimal("60.00"));
        assertTrue(payment.isInvoiceOutdated());

        // 55 y 55.00 son el mismo dinero: comparar con compareTo y no con equals.
        payment.setAmountDue(new BigDecimal("55"));
        assertFalse(payment.isInvoiceOutdated());
    }

    @Test
    void aChargeWithNoInvoiceNeverAsksToBeRectified() {
        Payment payment = Payment.builder().amountDue(new BigDecimal("55.00")).build();
        assertFalse(payment.isInvoiceOutdated());
    }
}
