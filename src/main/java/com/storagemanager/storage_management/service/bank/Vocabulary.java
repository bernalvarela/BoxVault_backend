package com.storagemanager.storage_management.service.bank;

import com.storagemanager.storage_management.model.enums.BankVocabularyList;
import com.storagemanager.storage_management.model.enums.ExpenseCategory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Las palabras con las que se leen los conceptos del banco, ya preparadas para
 * usarlas: conjuntos, el mes de cada palabra, las palabras de cada categoría de
 * gasto en su orden y la expresión que reconoce "TRASTERO 4".
 * <p>
 * Inmutable: cuando alguien cambia una lista se construye otra entera y se
 * cambia de golpe ({@link TextMatch#use}), así una importación a medias nunca
 * ve media lista vieja y media nueva.
 * <p>
 * {@link #DEFAULTS} son los valores de fábrica: con ellos se rellena la base la
 * primera vez, se restaura una lista y se ejecutan las pruebas.
 */
public final class Vocabulary {

    /** Una palabra (o frase) de una lista, con su mes o su categoría si la lista los lleva. */
    public record Entry(String word, String value) {
        public static Entry of(String word) { return new Entry(word, null); }
    }

    /**
     * Si un cargo lleva palabras de varias categorías, manda la primera de este
     * orden. Las comisiones del banco van primero ("COMISION MANTENIMIENTO" no es
     * una reparación) y el pago a la AEAT antes que los tributos locales, que
     * también dicen "TRIBUTOS". Las categorías que no salen aquí van detrás.
     */
    private static final List<ExpenseCategory> CATEGORY_PRIORITY = List.of(
            ExpenseCategory.OTROS, ExpenseCategory.IMPUESTOS, ExpenseCategory.TRIBUTOS, ExpenseCategory.SUMINISTROS,
            ExpenseCategory.SEGUROS, ExpenseCategory.COMUNIDAD, ExpenseCategory.REPARACIONES);

    public static final Map<BankVocabularyList, List<Entry>> DEFAULTS = factoryDefaults();

    private final Set<String> noise;
    private final Set<String> generic;
    private final Map<String, Integer> months;
    private final List<String> deposit;
    private final List<String> loan;
    private final List<String> ownerTransfer;
    private final Map<ExpenseCategory, List<String>> expenseWords;
    private final Pattern unitReference;

    private Vocabulary(Map<BankVocabularyList, List<Entry>> lists) {
        Map<BankVocabularyList, List<Entry>> all = new EnumMap<>(BankVocabularyList.class);
        all.putAll(DEFAULTS);
        all.putAll(lists);

        this.months = new LinkedHashMap<>();
        for (Entry e : all.getOrDefault(BankVocabularyList.MONTH, List.of())) {
            try {
                int month = Integer.parseInt(e.value());
                if (month >= 1 && month <= 12) months.put(e.word(), month);
            } catch (NumberFormatException ignored) {
                // Una fila sin mes no reconoce nada.
            }
        }
        List<String> markers = words(all, BankVocabularyList.NUMBER_MARKER);
        Set<String> noiseWords = new HashSet<>(words(all, BankVocabularyList.NOISE));
        // Los meses y las abreviaturas de número tampoco dicen quién paga.
        noiseWords.addAll(months.keySet());
        noiseWords.addAll(markers);
        this.noise = Set.copyOf(noiseWords);
        this.generic = Set.copyOf(words(all, BankVocabularyList.GENERIC));
        this.deposit = words(all, BankVocabularyList.DEPOSIT);
        this.loan = words(all, BankVocabularyList.LOAN);
        this.ownerTransfer = words(all, BankVocabularyList.OWNER_TRANSFER);

        Map<ExpenseCategory, List<String>> byCategory = new LinkedHashMap<>();
        CATEGORY_PRIORITY.forEach(c -> byCategory.put(c, new ArrayList<>()));
        for (Entry e : all.getOrDefault(BankVocabularyList.EXPENSE_WORD, List.of())) {
            try {
                byCategory.computeIfAbsent(ExpenseCategory.valueOf(e.value()), c -> new ArrayList<>()).add(e.word());
            } catch (IllegalArgumentException | NullPointerException ignored) {
                // Una categoría que ya no existe: la palabra no clasifica nada.
            }
        }
        byCategory.values().removeIf(List::isEmpty);
        byCategory.replaceAll((c, list) -> List.copyOf(list));
        this.expenseWords = Collections.unmodifiableMap(byCategory);

        this.unitReference = unitPattern(words(all, BankVocabularyList.UNIT_WORD), markers);
    }

    /** El vocabulario con estas listas; las que falten, con sus valores de fábrica. */
    public static Vocabulary of(Map<BankVocabularyList, List<Entry>> lists) {
        return new Vocabulary(lists);
    }

    public static Vocabulary defaults() {
        return new Vocabulary(Map.of());
    }

    private static List<String> words(Map<BankVocabularyList, List<Entry>> all, BankVocabularyList list) {
        return all.getOrDefault(list, List.of()).stream().map(Entry::word)
                .filter(w -> w != null && !w.isBlank()).distinct().toList();
    }

    /**
     * "(?:\bTRAST|\bBAIXO|...|TRASTERO|TRASTEIRO)(?:\s+(?:N|NR|NMR...))*\s+(\d{1,3}[A-Z]?)\b".
     * Las palabras largas, de 7 letras o más, pueden ir pegadas a la anterior
     * ("PAGOTRASTERO"); las cortas tienen que empezar palabra, o "PISO" saldría
     * dentro de "REPISO". Las más largas se prueban antes.
     */
    private static Pattern unitPattern(List<String> unitWords, List<String> markers) {
        if (unitWords.isEmpty()) return Pattern.compile("(?!x)x");
        Comparator<String> longestFirst = Comparator.comparingInt(String::length).reversed();
        String glued = unitWords.stream().filter(w -> w.length() >= 7).sorted(longestFirst)
                .map(Pattern::quote).collect(Collectors.joining("|"));
        String bounded = unitWords.stream().filter(w -> w.length() < 7).sorted(longestFirst)
                .map(Pattern::quote).collect(Collectors.joining("|"));
        List<String> alternatives = new ArrayList<>();
        if (!bounded.isEmpty()) alternatives.add("\\b(?:" + bounded + ")");
        if (!glued.isEmpty()) alternatives.add(glued);
        String marker = markers.isEmpty() ? "" : "(?:\\s+(?:" + markers.stream().sorted(longestFirst)
                .map(Pattern::quote).collect(Collectors.joining("|")) + "))*";
        return Pattern.compile("(?:" + String.join("|", alternatives) + ")" + marker + "\\s+(\\d{1,3}[A-Z]?)\\b");
    }

    public Set<String> noise() { return noise; }
    public Set<String> generic() { return generic; }
    public Map<String, Integer> months() { return months; }
    public List<String> deposit() { return deposit; }
    public List<String> loan() { return loan; }
    public List<String> ownerTransfer() { return ownerTransfer; }
    /** Las palabras de cada categoría, en el orden en que se prueban. */
    public Map<ExpenseCategory, List<String>> expenseWords() { return expenseWords; }
    public Pattern unitReference() { return unitReference; }

    // ------------------------------------------------------------- Valores de fábrica

    private static List<Entry> plain(String... words) {
        return java.util.Arrays.stream(words).map(Entry::of).toList();
    }

    private static Map<BankVocabularyList, List<Entry>> factoryDefaults() {
        Map<BankVocabularyList, List<Entry>> d = new EnumMap<>(BankVocabularyList.class);
        d.put(BankVocabularyList.UNIT_WORD, plain(
                "TRASTERO", "TRASTEIRO", "TRAST", "TRSTR", "BAIXO", "BAJO", "PISO", "LOCAL", "PLAZA", "GARAJE", "UNIDAD"));
        d.put(BankVocabularyList.NUMBER_MARKER, plain(
                "N", "NO", "NR", "NRO", "NMR", "NMRO", "NUM", "NUMR", "NUMERO", "MERO"));

        List<Entry> months = new ArrayList<>();
        String[][] names = {
                {"ENERO", "XANEIRO"}, {"FEBRERO", "FEBREIRO"}, {"MARZO"}, {"ABRIL"}, {"MAYO", "MAIO"},
                {"JUNIO", "XUNO"}, {"JULIO", "XULLO"}, {"AGOSTO"}, {"SEPTIEMBRE", "SETIEMBRE", "SETEMBRO"},
                {"OCTUBRE", "OUTUBRO"}, {"NOVIEMBRE", "NOVEMBRO"}, {"DICIEMBRE", "DECEMBRO"}};
        for (int m = 0; m < names.length; m++) {
            for (String name : names[m]) months.add(new Entry(name, String.valueOf(m + 1)));
        }
        d.put(BankVocabularyList.MONTH, List.copyOf(months));

        d.put(BankVocabularyList.DEPOSIT, plain("FIANZA", "FIANZAS"));
        d.put(BankVocabularyList.LOAN, plain("PRESTAMO", "HIPOTECA", "HIPOTECARIO"));
        d.put(BankVocabularyList.OWNER_TRANSFER, plain("TRASPASO", "REPARTO"));

        List<Entry> expense = new ArrayList<>();
        Map<ExpenseCategory, List<String>> byCategory = new LinkedHashMap<>();
        byCategory.put(ExpenseCategory.OTROS, List.of("COMISION", "COMISIONES", "MANTENIMIENTO CUENTA", "CUOTA TARJETA"));
        // "CARGO POR PAGO DE IMPUESTOS - TRIBUTOS NRC ...": el pago a la AEAT con su NRC.
        byCategory.put(ExpenseCategory.IMPUESTOS, List.of("AEAT", "AGENCIA TRIBUTARIA", "AGENCIA ESTATAL", "HACIENDA",
                "MODELO 303", "MOD 303", "IMPUESTO", "IMPUESTOS", "NRC"));
        // "TAXA OUTORGAMENTO DE LICENCIAS URBANISTICAS": en gallego, tasa es taxa.
        byCategory.put(ExpenseCategory.TRIBUTOS, List.of("IBI", "AYUNTAMIENTO", "CONCELLO", "RECAUDACION", "TRIBUTOS",
                "TASA", "TASAS", "TAXA", "TAXAS", "LICENCIA", "LICENCIAS", "BASURA", "DEPUTACION", "DIPUTACION"));
        byCategory.put(ExpenseCategory.SUMINISTROS, List.of("IGNIS", "IBERDROLA", "ENDESA", "NATURGY", "REPSOL", "EDP",
                "HOLALUZ", "TOTALENERGIES", "AQUALIA", "EMALCSA", "AUGAS", "AGUA", "LUZ", "ELECTRICIDAD", "TELEFONICA",
                "MOVISTAR", "VODAFONE", "ORANGE", "DIGI", "R CABLE", "FIBRA"));
        byCategory.put(ExpenseCategory.SEGUROS, List.of("SEGURO", "SEGUROS", "MAPFRE", "MUTUA", "ALLIANZ", "AXA",
                "GENERALI", "LINEA DIRECTA", "OCASO", "SANTALUCIA", "REALE", "CASER", "ZURICH", "PELAYO"));
        byCategory.put(ExpenseCategory.COMUNIDAD, List.of("COMUNIDAD", "CDAD", "COM PROP", "COMUNIDAD PROPIETARIOS"));
        byCategory.put(ExpenseCategory.REPARACIONES, List.of("REPARACION", "FONTANERO", "FONTANERIA", "ELECTRICISTA",
                "PINTURA", "CERRAJERO", "CERRAJERIA", "MANTENIMIENTO", "OBRA", "FERRETERIA", "LEROY"));
        byCategory.forEach((category, words) -> words.forEach(w -> expense.add(new Entry(w, category.name()))));
        d.put(BankVocabularyList.EXPENSE_WORD, List.copyOf(expense));

        d.put(BankVocabularyList.GENERIC, plain(
                "TRASTERO", "TRASTEIRO", "TRAST", "TRSTR", "BAIXO", "BAJO", "PISO", "LOCAL", "PLAZA", "GARAJE", "UNIDAD",
                "ALQUILER", "ALUGUER", "ARRENDAMIENTO", "RENTA", "MENSUALIDADE", "CUOTA", "PAGO", "NUMERO", "MERO",
                "AV", "AVDA", "AVENIDA", "PASAXE", "PASAJE", "PASAX", "PONTE", "IZDA", "DCHA", "IZQUIERDA", "DERECHA"));
        d.put(BankVocabularyList.NOISE, plain(
                "DE", "DEL", "LA", "LAS", "LOS", "EL", "Y", "E", "A", "AL", "EN", "POR", "PARA", "CON",
                "TRANSFERENCIA", "TRANSFERENCIAS", "TRANSF", "TRF", "TRANS", "INMEDIATA", "SEPA", "BIZUM",
                "RECIBO", "RECIBOS", "ADEUDO", "ADEUDOS", "ABONO", "ABONOS", "CARGO", "CARGOS", "COBRO", "PAGO",
                "SU", "CONCEPTO", "ORDENANTE", "BENEFICIARIO", "FAVOR", "REF", "REFERENCIA", "ES", "EUR", "EUROS",
                "MES", "MENSUALIDAD"));
        return Collections.unmodifiableMap(d);
    }
}
