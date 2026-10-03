package com.storagemanager.storage_management.service.bank;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Comparar el concepto de un movimiento con nombres y con reglas.
 * <p>
 * Los bancos escriben los conceptos en mayúsculas, sin tildes, cortados y con
 * ruido ("TRANSFERENCIA INMEDIATA DE GOMEZ PEREZ ANA CONCEPTO ALQUILER
 * OCTUBRE"). Por eso todo se compara normalizado y por palabras, y no como
 * texto: el orden de nombre y apellidos cambia de un banco a otro.
 */
public final class TextMatch {

    private TextMatch() {}

    /**
     * Palabras que no dicen quién paga ni qué es: partículas de los nombres y la
     * jerga de los bancos. Se quitan al aprender una regla para que valga el
     * mes que viene, cuando el concepto diga "NOVIEMBRE" en vez de "OCTUBRE".
     */
    private static final Set<String> NOISE = Set.copyOf(List.of(
            "DE", "DEL", "LA", "LAS", "LOS", "EL", "Y", "E", "A", "AL", "EN", "POR", "PARA", "CON",
            "TRANSFERENCIA", "TRANSFERENCIAS", "TRANSF", "TRF", "TRANS", "INMEDIATA", "SEPA", "BIZUM",
            "RECIBO", "RECIBOS", "ADEUDO", "ADEUDOS", "ABONO", "ABONOS", "CARGO", "CARGOS", "COBRO", "PAGO",
            "SU", "CONCEPTO", "ORDENANTE", "BENEFICIARIO", "FAVOR", "REF",
            "REFERENCIA", "N", "NO", "NR", "NRO", "NMR", "NMRO", "NUM", "NUMR", "NUMERO", "ES", "EUR", "EUROS", "MES", "MENSUALIDAD",
            "ENERO", "FEBRERO", "MARZO", "ABRIL", "MAYO", "JUNIO", "JULIO", "AGOSTO", "SEPTIEMBRE",
            "SETIEMBRE", "OCTUBRE", "NOVIEMBRE", "DICIEMBRE",
            // Los meses en gallego que no se escriben igual que en castellano.
            "XANEIRO", "FEBREIRO", "MAIO", "XUNO", "XULLO", "SETEMBRO", "OUTUBRO", "NOVEMBRO", "DECEMBRO"));

    /**
     * "Gómez Pérez, Ana" -> "GOMEZ PEREZ ANA": mayúsculas, sin tildes, sin signos.
     * Las letras y las cifras pegadas se separan ("préstamo3202659458" ->
     * "PRESTAMO 3202659458", "TRASTERO8" -> "TRASTERO 8"): hay bancos que no
     * dejan espacio y la palabra tiene que reconocerse igual.
     */
    public static String normalize(String text) {
        if (text == null) return "";
        String plain = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return plain.toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9Ñ]+", " ")
                .replaceAll("(?<=[A-Z])(?=\\d)|(?<=\\d)(?=[A-Z])", " ")
                .trim().replaceAll("\\s+", " ");
    }

    /** Las palabras de un texto normalizado. */
    public static Set<String> words(String text) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) return Set.of();
        return new LinkedHashSet<>(Arrays.asList(normalized.split(" ")));
    }

    /**
     * Las palabras de un nombre que sirven para reconocerlo: sin partículas y
     * de tres letras o más ("de", "la" y "Y" no dicen nada).
     */
    public static List<String> nameWords(String fullName) {
        return words(fullName).stream()
                .filter(w -> w.length() >= 3 && !NOISE.contains(w))
                .toList();
    }

    /**
     * Cuántas palabras de un nombre salen en el concepto. Un nombre se da por
     * reconocido con dos (nombre y un apellido, o los dos apellidos), o con la
     * única que tenga si es de una sola.
     */
    public static int nameScore(String fullName, Set<String> conceptWords) {
        List<String> name = nameWords(fullName);
        if (name.isEmpty()) return 0;
        int hits = (int) name.stream().filter(conceptWords::contains).count();
        boolean recognized = hits >= 2 || (name.size() == 1 && hits == 1);
        return recognized ? hits : 0;
    }

    /**
     * La clave que se guarda al aprender una regla: las palabras del concepto
     * sin cifras, fechas, meses ni jerga bancaria. "TRANSFERENCIA DE PEREZ SOUTO
     * CARMEN ALQUILER 3 IZDA OCTUBRE 2026" -> "PEREZ SOUTO CARMEN ALQUILER IZDA".
     */
    public static String learnKey(String concept) {
        List<String> kept = words(concept).stream()
                .filter(w -> !w.matches(".*\\d.*"))
                .filter(w -> w.length() >= 2 && !NOISE.contains(w))
                .limit(8)
                .toList();
        // Si solo quedan palabras que escribe cualquier inquilino ("TRASTERO",
        // "ALQUILER"), la regla atraparía los pagos de todos: no se aprende.
        if (kept.stream().allMatch(GENERIC::contains)) return "";
        String key = String.join(" ", kept);
        return key.length() > 200 ? key.substring(0, 200).trim() : key;
    }

    /**
     * Palabras del alquiler que no distinguen a nadie: las escribe cualquiera que
     * pague un trastero o un piso de la casa. Pueden ir en una regla junto a otras,
     * pero una regla hecha solo de ellas valdría para todos.
     */
    private static final Set<String> GENERIC = Set.copyOf(List.of(
            "TRASTERO", "TRASTEIRO", "TRAST", "BAIXO", "BAJO", "PISO", "LOCAL", "PLAZA", "GARAJE", "UNIDAD",
            "ALQUILER", "ALUGUER", "ARRENDAMIENTO", "RENTA", "MENSUALIDADE", "CUOTA", "PAGO", "NUMERO", "MERO",
            "AV", "AVDA", "AVENIDA", "PASAXE", "PASAJE", "PASAX", "PONTE", "IZDA", "DCHA", "IZQUIERDA", "DERECHA"));

    /** Si el concepto lleva todas las palabras de la regla, en cualquier orden. */
    public static boolean matchesRule(String pattern, Set<String> conceptWords) {
        if (pattern == null || pattern.isBlank()) return false;
        return Arrays.stream(pattern.split(" ")).allMatch(conceptWords::contains);
    }

    /**
     * "TRASTERO 8", "Trasteiro 7", "trastero número 4", "Baixo 7", "piso 3": lo que
     * de verdad escribe quien paga, mucho más a menudo que su nombre. Entre la
     * palabra y el número puede haber "nº", "número" (o lo que quede de él si el
     * banco se comió la tilde: "n.mero") o una abreviatura ("nr", "nmr").
     * "Trastero" se reconoce también pegado a la palabra de antes
     * ("PAGOTRASTERO NMR 4"): es lo bastante largo para no salir dentro de otra.
     */
    private static final Pattern UNIT_REFERENCE = Pattern.compile(
            "(?:\\b(?:TRAST|BAIXO|BAJO|PISO|LOCAL|PLAZA|GARAJE|UNIDAD)|TRASTERO|TRASTEIRO)"
            + "(?:\\s+(?:N|NO|NR|NRO|NMR|NMRO|NUM|NUMR|NUMERO|MERO))*\\s+(\\d{1,3}[A-Z]?)\\b");

    /** Los números de unidad que se nombran en el concepto, en orden: "TRASTERO 8" -> ["8"]. */
    public static List<String> unitReferences(String concept) {
        Matcher m = UNIT_REFERENCE.matcher(normalize(concept));
        List<String> found = new ArrayList<>();
        while (m.find()) {
            if (!found.contains(m.group(1))) found.add(m.group(1));
        }
        return found;
    }

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("ENERO", 1), Map.entry("XANEIRO", 1),
            Map.entry("FEBRERO", 2), Map.entry("FEBREIRO", 2),
            Map.entry("MARZO", 3),
            Map.entry("ABRIL", 4),
            Map.entry("MAYO", 5), Map.entry("MAIO", 5),
            Map.entry("JUNIO", 6), Map.entry("XUNO", 6),
            Map.entry("JULIO", 7), Map.entry("XULLO", 7),
            Map.entry("AGOSTO", 8),
            Map.entry("SEPTIEMBRE", 9), Map.entry("SETIEMBRE", 9), Map.entry("SETEMBRO", 9),
            Map.entry("OCTUBRE", 10), Map.entry("OUTUBRO", 10),
            Map.entry("NOVIEMBRE", 11), Map.entry("NOVEMBRO", 11),
            Map.entry("DICIEMBRE", 12), Map.entry("DECEMBRO", 12));

    /** Los meses que nombra el concepto, en orden: "Mes de septiembre y octubre" -> [9, 10]. */
    public static List<Integer> monthsIn(String concept) {
        List<Integer> found = new ArrayList<>();
        for (String word : normalize(concept).split(" ")) {
            Integer month = MONTHS.get(word);
            if (month != null && !found.contains(month)) found.add(month);
        }
        return found;
    }

    /** El primer año que se nombra ("OCTUBRE 2026"), o nulo. */
    public static Integer yearIn(String concept) {
        Matcher m = Pattern.compile("\\b(20\\d{2})\\b").matcher(normalize(concept));
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    /** Si el concepto contiene alguna de estas palabras o frases (ya normalizadas). */
    public static boolean containsAny(String normalizedConcept, List<String> needles) {
        String padded = " " + normalizedConcept + " ";
        return needles.stream().anyMatch(n -> padded.contains(" " + n + " "));
    }
}
