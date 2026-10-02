package com.storagemanager.storage_management;

import com.storagemanager.storage_management.model.Expense;
import com.storagemanager.storage_management.model.enums.ExpenseCategory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** De la cuota de la hipoteca solo son gasto en el IRPF sus intereses. */
class MortgageExpenseTest {

    private static Expense expense(ExpenseCategory category, String amount, String interest, String vat) {
        return Expense.builder().category(category).amount(new BigDecimal(amount))
                .interestAmount(interest == null ? null : new BigDecimal(interest))
                .vatAmount(vat == null ? null : new BigDecimal(vat)).build();
    }

    @Test
    void onlyTheInterestOfAMortgageInstalmentIsAnExpense() {
        Expense cuota = expense(ExpenseCategory.HIPOTECA, "520.00", "143.27", null);
        assertEquals(new BigDecimal("143.27"), cuota.irpfAmount());
        assertEquals(new BigDecimal("376.73"), cuota.irpfExcludedAmount());
    }

    @Test
    void withoutTheInterestNothingIsDeducted() {
        Expense cuota = expense(ExpenseCategory.HIPOTECA, "520.00", null, null);
        assertEquals(0, cuota.irpfAmount().signum());
        assertEquals(new BigDecimal("520.00"), cuota.irpfExcludedAmount());
    }

    @Test
    void otherExpensesStillCountWithoutTheirVat() {
        Expense luz = expense(ExpenseCategory.SUMINISTROS, "121.00", "50.00", "21.00");
        assertEquals(new BigDecimal("100.00"), luz.irpfAmount());
        assertEquals(0, luz.irpfExcludedAmount().signum());
    }
}
