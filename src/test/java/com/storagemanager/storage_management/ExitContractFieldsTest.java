package com.storagemanager.storage_management;

import com.storagemanager.storage_management.config.InvoicingProperties;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.service.pdf.ContractFields;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lo que el contrato de salida dice de la fianza. Es texto que se firma: una
 * cifra mal puesta aquí es un finiquito equivocado con la firma del inquilino.
 */
class ExitContractFieldsTest {

    /** El alquiler de ejemplo (fianza de 110,00 €) con lo decidido al cerrar. */
    private static Map<String, String> fieldsFor(Boolean returned, String amount, String notes) {
        RentalAgreement rental = ContractFields.sampleRental();
        rental.setDepositReturned(returned);
        rental.setDepositReturnedAmount(amount == null ? null : new BigDecimal(amount));
        rental.setDepositReturnNotes(notes);
        return ContractFields.of(rental, ContractFields.sampleIssuer());
    }

    @Test
    void theWholeDepositBack() {
        Map<String, String> f = fieldsFor(true, "110.00", null);
        assertEquals("110,00 €", f.get("fianza_devuelta"));
        assertEquals("0,00 €", f.get("fianza_retenida"));
        assertTrue(f.get("fianza_devolucion_texto").contains("la totalidad de la fianza, 110,00 €"),
                f.get("fianza_devolucion_texto"));
    }

    @Test
    void partOfTheDepositBackSaysWhatIsKeptAndWhy() {
        Map<String, String> f = fieldsFor(true, "80.00", "Pintura de una pared");
        assertEquals("80,00 €", f.get("fianza_devuelta"));
        assertEquals("30,00 €", f.get("fianza_retenida"));
        assertTrue(f.get("fianza_devolucion_texto").contains("80,00 € de los 110,00 €"), f.get("fianza_devolucion_texto"));
        assertTrue(f.get("fianza_devolucion_texto").contains("retiene 30,00 €"), f.get("fianza_devolucion_texto"));
        assertEquals("Pintura de una pared", f.get("fianza_motivo"));
    }

    @Test
    void nothingBack() {
        Map<String, String> f = fieldsFor(false, "0.00", "Tres mensualidades sin pagar");
        assertEquals("0,00 €", f.get("fianza_devuelta"));
        assertEquals("110,00 €", f.get("fianza_retenida"));
        assertTrue(f.get("fianza_devolucion_texto").contains("retiene la totalidad"), f.get("fianza_devolucion_texto"));
        assertEquals("Tres mensualidades sin pagar", f.get("fianza_motivo"));
    }

    /**
     * Sin nada retenido, el motivo no sale aunque se escribiera una nota: si no,
     * la plantilla imprimiría «la cantidad retenida, 0,00 €, se aplica a...».
     */
    @Test
    void noReasonIsPrintedWhenNothingIsKept() {
        Map<String, String> f = fieldsFor(true, "110.00", "Todo en orden");
        assertEquals(InvoicingProperties.MISSING, f.get("fianza_motivo"));
    }

    /** Un contrato que sigue vivo no tiene nada decidido: la cantidad queda como hueco a la vista. */
    @Test
    void undecidedLeavesTheAmountVisiblyBlank() {
        Map<String, String> f = fieldsFor(null, null, null);
        assertEquals(InvoicingProperties.MISSING, f.get("fianza_devuelta"));
        assertEquals(InvoicingProperties.MISSING, f.get("fianza_retenida"));
    }

    /** Todos los campos del catálogo tienen valor al componer: la lista del editor no puede mentir. */
    @Test
    void everyCatalogueFieldIsFilled() {
        Map<String, String> f = fieldsFor(true, "80.00", "Pintura");
        for (ContractFields.Field field : ContractFields.CATALOGUE) {
            if (field.name().contains("[")) continue; // los numerados dependen de cuántos firmen
            assertTrue(f.containsKey(field.name()), "el campo {{" + field.name() + "}} no se rellena nunca");
        }
    }
}
