package com.storagemanager.storage_management.service.bank;

import com.storagemanager.storage_management.model.BankImportProfile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Lee las filas de un extracto con la descripción de su perfil: qué columna es
 * la fecha, cuál el concepto, cuál el importe.
 * <p>
 * Las filas llegan ya sacadas del fichero por el navegador, cada celda como
 * texto. Aquí no se sabe nada de xls ni de csv: solo de columnas. Las filas que
 * no son movimientos -la cabecera, los títulos que ponen algunos bancos encima,
 * los totales del pie- se saltan; las que parecen movimientos pero no se
 * pueden leer se avisan, para que no se pierda ninguno sin saberlo.
 * <p>
 * Sin estado y sin base de datos: se puede probar con unas filas de mentira.
 */
public final class BankStatementParser {

    /**
     * Un movimiento leído del fichero.
     *
     * @param reference el identificador del movimiento en el banco (la "Remesa"
     *                  de BBVA), si el perfil dice dónde está; nulo si no
     */
    public record Movement(int lineNumber, LocalDate date, LocalDate valueDate, String concept,
                           BigDecimal amount, BigDecimal balance, String reference) {

        public Movement(int lineNumber, LocalDate date, LocalDate valueDate, String concept,
                        BigDecimal amount, BigDecimal balance) {
            this(lineNumber, date, valueDate, concept, amount, balance, null);
        }
    }

    /** Lo leído y lo que no se pudo leer. */
    public record Result(List<Movement> movements, List<String> warnings) {}

    private BankStatementParser() {}

    public static Result parse(BankImportProfile profile, List<List<String>> rows) {
        List<Movement> movements = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int firstDataRow = profile.getHeaderRow() == null ? 1 : profile.getHeaderRow();
        DateTimeFormatter format = formatter(profile.getDateFormat());
        boolean decimalComma = !Boolean.FALSE.equals(profile.getDecimalComma());

        for (int i = firstDataRow; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            int lineNumber = i + 1;
            String rawDate = cell(row, profile.getDateColumn());
            String rawAmount = profile.getAmountColumn() != null ? cell(row, profile.getAmountColumn()) : null;
            String rawDebit = cell(row, profile.getDebitColumn());
            String rawCredit = cell(row, profile.getCreditColumn());
            boolean noAmount = isBlank(rawAmount) && isBlank(rawDebit) && isBlank(rawCredit);

            // Una fila vacía, o un título o un total sin fecha: no es un movimiento.
            if (isBlank(rawDate) && noAmount) continue;
            LocalDate date = parseDate(rawDate, format);
            if (date == null) {
                if (!noAmount) warnings.add("Fila " + lineNumber + ": no se entiende la fecha «" + rawDate + "»");
                continue;
            }

            BigDecimal amount;
            try {
                if (profile.getAmountColumn() != null) {
                    amount = parseAmount(rawAmount, decimalComma);
                } else {
                    BigDecimal debit = parseAmount(rawDebit, decimalComma);
                    BigDecimal credit = parseAmount(rawCredit, decimalComma);
                    // El cargo sale en positivo en unos bancos y en negativo en otros.
                    amount = (credit == null ? BigDecimal.ZERO : credit.abs())
                            .subtract(debit == null ? BigDecimal.ZERO : debit.abs());
                }
            } catch (NumberFormatException e) {
                warnings.add("Fila " + lineNumber + ": no se entiende el importe");
                continue;
            }
            if (amount == null || amount.signum() == 0) {
                warnings.add("Fila " + lineNumber + ": sin importe, se salta");
                continue;
            }

            String concept = joinConcept(cell(row, profile.getConceptColumn()), cell(row, profile.getConceptExtraColumn()));
            if (concept.isEmpty()) concept = "(sin concepto)";

            BigDecimal balance = null;
            try {
                balance = parseAmount(cell(row, profile.getBalanceColumn()), decimalComma);
            } catch (NumberFormatException ignored) {
                // El saldo solo sirve para distinguir dos movimientos iguales del mismo día.
            }

            String reference = cell(row, profile.getReferenceColumn());
            if (reference != null && (reference.isBlank() || reference.length() > 60)) reference = null;

            movements.add(new Movement(lineNumber, date, parseDate(cell(row, profile.getValueDateColumn()), format),
                    concept, amount.setScale(2, RoundingMode.HALF_UP),
                    balance == null ? null : balance.setScale(2, RoundingMode.HALF_UP), reference));
        }
        return new Result(movements, warnings);
    }

    /**
     * La huella de un movimiento: cuenta, fecha, importe, concepto y saldo. Dos
     * filas con la misma huella son el mismo movimiento importado dos veces. El
     * saldo distingue dos cobros iguales del mismo día, que sí son dos.
     */
    public static String fingerprint(Long profileId, Movement m) {
        // Si el banco da un identificador por movimiento, ese es el movimiento: no
        // depende de que el concepto o el saldo se exporten igual otra vez.
        String key = m.reference() != null
                ? profileId + "|REF|" + m.reference().trim().toUpperCase(Locale.ROOT)
                : profileId + "|" + m.date() + "|" + m.amount().toPlainString() + "|"
                    + TextMatch.normalize(m.concept()) + "|" + (m.balance() == null ? "" : m.balance().toPlainString());
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    // ------------------------------------------------------------------------

    private static String cell(List<String> row, Integer column) {
        if (column == null || row == null || column < 0 || column >= row.size()) return null;
        String value = row.get(column);
        return value == null ? null : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String joinConcept(String main, String extra) {
        String a = main == null ? "" : main.replaceAll("\\s+", " ").trim();
        String b = extra == null ? "" : extra.replaceAll("\\s+", " ").trim();
        // El Excel de BBVA repite el mismo texto en dos columnas con distintas
        // mayúsculas ("Traspaso mensual xiao" / "TRASPASO MENSUAL XIAO"): si uno
        // está dentro del otro, se queda solo el más largo.
        String na = TextMatch.normalize(a);
        String nb = TextMatch.normalize(b);
        if (b.isEmpty() || na.contains(nb)) return truncate(a);
        if (a.isEmpty() || nb.contains(na)) return truncate(b);
        return truncate(a + " · " + b);
    }

    private static String truncate(String text) {
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    private static DateTimeFormatter formatter(String pattern) {
        try {
            return DateTimeFormatter.ofPattern(pattern == null || pattern.isBlank() ? "dd/MM/yyyy" : pattern, Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT);
        }
    }

    /**
     * La fecha de una celda: la de Excel, que el navegador ya manda como
     * yyyy-mm-dd, o el texto en el formato del perfil. Se prueban también las
     * variantes de siempre (barras o guiones, año de dos o cuatro cifras) porque
     * un mismo banco no siempre escribe igual en xls que en csv.
     */
    static LocalDate parseDate(String raw, DateTimeFormatter format) {
        if (isBlank(raw)) return null;
        String text = raw.trim();
        // "2026-10-02T00:00:00" o "2026-10-02 00:00" de algunas hojas: la fecha es lo primero.
        if (text.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
            try {
                return LocalDate.parse(text.substring(0, 10));
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
        for (DateTimeFormatter f : List.of(format,
                DateTimeFormatter.ofPattern("d/M/yyyy", Locale.ROOT),
                DateTimeFormatter.ofPattern("d-M-yyyy", Locale.ROOT),
                DateTimeFormatter.ofPattern("d/M/yy", Locale.ROOT),
                DateTimeFormatter.ofPattern("d-M-yy", Locale.ROOT),
                DateTimeFormatter.ofPattern("d.M.yyyy", Locale.ROOT))) {
            try {
                return LocalDate.parse(text, f);
            } catch (DateTimeParseException ignored) {
                // la siguiente
            }
        }
        return null;
    }

    /**
     * Un importe escrito como lo escriba el banco: "1.234,56", "-1234.56",
     * "1 234,56 €", "(45,00)". Con punto y coma a la vez, el último es el
     * decimal. Con uno solo, decide el perfil: "1.234" es mil doscientos con
     * coma decimal y uno coma dos sin ella.
     */
    static BigDecimal parseAmount(String raw, boolean decimalComma) {
        if (isBlank(raw)) return null;
        String text = raw.trim().replace("€", "").replace("EUR", "").replace(" ", "").replace(" ", "");
        boolean negative = false;
        if (text.startsWith("(") && text.endsWith(")")) {
            negative = true;
            text = text.substring(1, text.length() - 1);
        }
        if (text.endsWith("-")) {
            negative = true;
            text = text.substring(0, text.length() - 1);
        }
        if (text.startsWith("+")) text = text.substring(1);
        if (text.startsWith("-")) {
            negative = !negative;
            text = text.substring(1);
        }

        int lastComma = text.lastIndexOf(',');
        int lastDot = text.lastIndexOf('.');
        if (lastComma >= 0 && lastDot >= 0) {
            if (lastComma > lastDot) text = text.replace(".", "").replace(',', '.');
            else text = text.replace(",", "");
        } else if (lastComma >= 0) {
            text = decimalComma || !text.matches("\\d{1,3}(,\\d{3})+")
                    ? text.replace(',', '.') : text.replace(",", "");
        } else if (lastDot >= 0 && decimalComma && text.matches("\\d{1,3}(\\.\\d{3})+")) {
            // Con coma decimal, "1.234" son miles. Un número que viene de Excel
            // nunca tiene esa forma: llega como "1234" o "1234.5".
            text = text.replace(".", "");
        }
        BigDecimal value = new BigDecimal(text);
        return negative ? value.negate() : value;
    }
}
