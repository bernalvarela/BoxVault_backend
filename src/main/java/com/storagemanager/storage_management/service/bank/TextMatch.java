package com.storagemanager.storage_management.service.bank;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Comparar el concepto de un movimiento con nombres y con reglas.
 * <p>
 * Los bancos escriben los conceptos en mayúsculas, sin tildes, cortados y con
 * ruido ("TRANSFERENCIA INMEDIATA DE GOMEZ PEREZ ANA CONCEPTO ALQUILER
 * OCTUBRE"). Por eso todo se compara normalizado y por palabras, y no como
 * texto: el orden de nombre y apellidos cambia de un banco a otro.
 * <p>
 * Las palabras que hay que conocer (las de unidad, los meses, la jerga del
 * banco...) no están aquí sino en el {@link Vocabulary} vigente, que se edita
 * desde la aplicación y se cambia con {@link #use}. Hasta que se carga el de la
 * base, y en las pruebas, rige el de fábrica.
 */
public final class TextMatch {

    private TextMatch() {}

    private static volatile Vocabulary vocabulary = Vocabulary.defaults();

    /** Cambia el vocabulario de golpe: la próxima lectura ya usa el nuevo. */
    public static void use(Vocabulary next) {
        vocabulary = Objects.requireNonNull(next);
    }

    public static Vocabulary vocabulary() {
        return vocabulary;
    }

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
        Set<String> noise = vocabulary.noise();
        return words(fullName).stream()
                .filter(w -> w.length() >= 3 && !noise.contains(w))
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
        Vocabulary v = vocabulary;
        List<String> kept = words(concept).stream()
                .filter(w -> !w.matches(".*\\d.*"))
                .filter(w -> w.length() >= 2 && !v.noise().contains(w))
                .limit(8)
                .toList();
        // Si solo quedan palabras que escribe cualquier inquilino ("TRASTERO",
        // "ALQUILER"), la regla atraparía los pagos de todos: no se aprende.
        if (kept.stream().allMatch(v.generic()::contains)) return "";
        String key = String.join(" ", kept);
        return key.length() > 200 ? key.substring(0, 200).trim() : key;
    }

    /** Si el concepto lleva todas las palabras de la regla, en cualquier orden. */
    public static boolean matchesRule(String pattern, Set<String> conceptWords) {
        if (pattern == null || pattern.isBlank()) return false;
        return Arrays.stream(pattern.split(" ")).allMatch(conceptWords::contains);
    }

    /**
     * Los números de unidad que se nombran en el concepto, en orden: "TRASTERO 8"
     * -> ["8"]. Lo que de verdad escribe quien paga, mucho más a menudo que su
     * nombre: "Trasteiro 7", "trastero número 4", "Pagotrastero nmr 4", "Baixo 7".
     */
    public static List<String> unitReferences(String concept) {
        Matcher m = vocabulary.unitReference().matcher(normalize(concept));
        List<String> found = new ArrayList<>();
        while (m.find()) {
            if (!found.contains(m.group(1))) found.add(m.group(1));
        }
        return found;
    }

    /** Los meses que nombra el concepto, en orden: "Mes de septiembre y octubre" -> [9, 10]. */
    public static List<Integer> monthsIn(String concept) {
        var months = vocabulary.months();
        List<Integer> found = new ArrayList<>();
        for (String word : normalize(concept).split(" ")) {
            Integer month = months.get(word);
            if (month != null && !found.contains(month)) found.add(month);
        }
        return found;
    }

    private static final Pattern YEAR = Pattern.compile("\\b(20\\d{2})\\b");

    /** El primer año que se nombra ("OCTUBRE 2026"), o nulo. */
    public static Integer yearIn(String concept) {
        Matcher m = YEAR.matcher(normalize(concept));
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    /** Si el concepto contiene alguna de estas palabras o frases (ya normalizadas). */
    public static boolean containsAny(String normalizedConcept, List<String> needles) {
        String padded = " " + normalizedConcept + " ";
        return needles.stream().anyMatch(n -> padded.contains(" " + n + " "));
    }
}
