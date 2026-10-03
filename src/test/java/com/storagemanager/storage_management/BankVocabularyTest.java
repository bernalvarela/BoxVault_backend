package com.storagemanager.storage_management;

import com.storagemanager.storage_management.model.enums.BankVocabularyList;
import com.storagemanager.storage_management.model.enums.ExpenseCategory;
import com.storagemanager.storage_management.service.bank.TextMatch;
import com.storagemanager.storage_management.service.bank.Vocabulary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * El vocabulario del banco: el de fábrica lee lo de siempre, y una palabra que
 * se añade desde la aplicación vale en cuanto se cambia, sin recompilar.
 */
class BankVocabularyTest {

    @AfterEach
    void backToDefaults() {
        TextMatch.use(Vocabulary.defaults());
    }

    private static Vocabulary withExtra(BankVocabularyList list, Vocabulary.Entry... extra) {
        List<Vocabulary.Entry> entries = new ArrayList<>(Vocabulary.DEFAULTS.get(list));
        entries.addAll(List.of(extra));
        return Vocabulary.of(Map.of(list, entries));
    }

    @Test
    void theFactoryVocabularyReadsWhatItAlwaysRead() {
        assertEquals(List.of("4"), TextMatch.unitReferences("Pagotrastero nmr 4 Av de Oza"));
        assertEquals(List.of("8"), TextMatch.unitReferences("TRASTERO8"));
        assertEquals(List.of(), TextMatch.unitReferences("REPISO 3"), "las palabras cortas no van pegadas");
        assertEquals(List.of(7, 8), TextMatch.monthsIn("xullo e agosto"));
        assertEquals("PEREZ SOUTO CARMEN ALQUILER IZDA",
                TextMatch.learnKey("TRANSFERENCIA DE PEREZ SOUTO CARMEN ALQUILER 3 IZDA OCTUBRE 2026"));
        assertEquals(ExpenseCategory.OTROS, TextMatch.vocabulary().expenseWords().keySet().iterator().next(),
                "las comisiones se prueban antes que nada");
    }

    @Test
    void aNewNumberMarkerWorksAsSoonAsItIsUsed() {
        assertEquals(List.of(), TextMatch.unitReferences("trastero numerito 5"));
        TextMatch.use(withExtra(BankVocabularyList.NUMBER_MARKER, Vocabulary.Entry.of("NUMERITO")));
        assertEquals(List.of("5"), TextMatch.unitReferences("trastero numerito 5"));
    }

    @Test
    void aNewMonthSpellingAndAnExpenseWordToo() {
        TextMatch.use(withExtra(BankVocabularyList.MONTH, new Vocabulary.Entry("SEPT", "9")));
        assertEquals(List.of(9), TextMatch.monthsIn("cuota sept"));
        assertTrue(TextMatch.learnKey("GOMEZ SEPT").equals("GOMEZ"), "un mes no entra en una regla");

        TextMatch.use(withExtra(BankVocabularyList.EXPENSE_WORD, new Vocabulary.Entry("GAS NATURAL", "SUMINISTROS")));
        assertTrue(TextMatch.vocabulary().expenseWords().get(ExpenseCategory.SUMINISTROS).contains("GAS NATURAL"));
    }

    @Test
    void anUnknownCategoryOrMonthIsIgnoredNotFatal() {
        Vocabulary v = Vocabulary.of(Map.of(
                BankVocabularyList.EXPENSE_WORD, List.of(new Vocabulary.Entry("ALGO", "NO_EXISTE")),
                BankVocabularyList.MONTH, List.of(new Vocabulary.Entry("RARO", "13"))));
        assertTrue(v.expenseWords().isEmpty());
        assertTrue(v.months().isEmpty());
    }
}
