package com.storagemanager.storage_management;

import com.storagemanager.storage_management.model.BankImportProfile;
import com.storagemanager.storage_management.model.enums.BankProfileContext;
import com.storagemanager.storage_management.service.bank.BankStatementParser;
import com.storagemanager.storage_management.service.bank.TextMatch;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Leer extractos de bancos distintos con su perfil, y reconocer nombres en los
 * conceptos. Sin base de datos: filas de mentira con las formas de verdad.
 */
class BankStatementParserTest {

    /** Como el BBVA: títulos encima, cabecera en la fila 5, importe con signo y coma decimal. */
    private static BankImportProfile bbvaLike() {
        return BankImportProfile.builder()
                .id(1L).name("BBVA pisos").context(BankProfileContext.PROPIETARIOS)
                .headerRow(5).dateColumn(0).valueDateColumn(1).conceptColumn(2).conceptExtraColumn(3)
                .amountColumn(4).balanceColumn(6).dateFormat("dd/MM/yyyy").decimalComma(true)
                .build();
    }

    @Test
    void readsASignedAmountStatementSkippingTitlesAndTotals() {
        List<List<String>> rows = List.of(
                List.of("Últimos movimientos"),
                List.of("Cuenta ES12 0182 ****"),
                List.of(),
                List.of("Desde 01/09/2026 hasta 30/09/2026"),
                List.of("F.Contable", "F.Valor", "Concepto", "Movimiento", "Importe", "Divisa", "Disponible"),
                List.of("02/09/2026", "02/09/2026", "Transferencia recibida", "GOMEZ PEREZ ANA ALQUILER SEPTIEMBRE", "590,00", "EUR", "1.590,00"),
                // De una celda de Excel: fecha ISO y número con punto.
                List.of("2026-09-05", "2026-09-05", "Recibo IBERDROLA", "", "-45.37", "EUR", "1544.63"),
                List.of("", "", "Total", "", "544,63", "", ""));

        BankStatementParser.Result result = BankStatementParser.parse(bbvaLike(), rows);

        assertEquals(2, result.movements().size(), "los títulos y el total no son movimientos: " + result);
        BankStatementParser.Movement rent = result.movements().get(0);
        assertEquals(LocalDate.of(2026, 9, 2), rent.date());
        assertEquals(new BigDecimal("590.00"), rent.amount());
        assertEquals(new BigDecimal("1590.00"), rent.balance());
        assertEquals("Transferencia recibida · GOMEZ PEREZ ANA ALQUILER SEPTIEMBRE", rent.concept());
        assertEquals(6, rent.lineNumber(), "la fila del fichero, para encontrarla en el original");

        BankStatementParser.Movement bill = result.movements().get(1);
        assertEquals(LocalDate.of(2026, 9, 5), bill.date());
        assertEquals(new BigDecimal("-45.37"), bill.amount());
    }

    /**
     * El csv de verdad de BBVA (la cuenta de los trasteros): cabecera en la fila
     * 1, punto decimal, el texto de quien paga en "Observaciones" y un
     * identificador por movimiento en "Remesa". Los nombres son inventados.
     */
    @Test
    void readsTheRealBbvaCsvLayout() {
        BankImportProfile bbva = BankImportProfile.builder()
                .id(5L).name("BBVA trasteros").context(BankProfileContext.PROPIETARIOS)
                .headerRow(1).dateColumn(0).valueDateColumn(1).conceptColumn(3).conceptExtraColumn(4)
                .amountColumn(6).balanceColumn(7).referenceColumn(8).dateFormat("dd/MM/yyyy").decimalComma(false)
                .build();
        List<List<String>> rows = List.of(
                List.of("Fecha Proceso", "Fecha Valor", "Código", "Concepto", "Observaciones", "Oficina", "Importe", "Saldo", "Remesa"),
                List.of("01/10/2026", "01/10/2026", "00007", "TRANSFERENCIAS", "OCTUBRE 2026 TRASTERO 8", "1128", "60.0", "4946.78", "ES0182140000071785148009"),
                List.of("24/09/2026", "24/09/2026", "00136", "ADEUDO A SU CARGO", "N 2026267000081741 IGNIS LUZ", "2201", "-14.63", "4811.78", "ES0182140000071596235494"),
                List.of("08/09/2026", "07/09/2026", "00007", "TRANSFERENCIAS", "trastero n.mero 4", "1128", "50.0", "8534.14", "ES0182140000071269330850"));

        List<BankStatementParser.Movement> movements = BankStatementParser.parse(bbva, rows).movements();

        assertEquals(3, movements.size());
        BankStatementParser.Movement rent = movements.get(0);
        assertEquals(new BigDecimal("60.00"), rent.amount(), "60.0 con punto decimal son sesenta euros");
        assertEquals(new BigDecimal("4946.78"), rent.balance());
        assertEquals("TRANSFERENCIAS · OCTUBRE 2026 TRASTERO 8", rent.concept());
        assertEquals("ES0182140000071785148009", rent.reference());
        assertEquals(new BigDecimal("-14.63"), movements.get(1).amount());
        assertEquals(LocalDate.of(2026, 9, 7), movements.get(2).valueDate());

        // Con identificador del banco, el duplicado se reconoce aunque el concepto o
        // el saldo se exporten distintos otra vez.
        BankStatementParser.Movement reExported = new BankStatementParser.Movement(40, rent.date(), null,
                "OTRO TEXTO", rent.amount(), null, rent.reference());
        assertEquals(BankStatementParser.fingerprint(5L, rent), BankStatementParser.fingerprint(5L, reExported));
    }

    /**
     * El Excel de BBVA («Últimos movimientos»): títulos encima, cabecera en la
     * fila 5, la fecha en B, importes como números de Excel, y el mismo texto
     * en dos columnas con distintas mayúsculas, que no debe salir dos veces.
     */
    @Test
    void readsTheBbvaExcelReport() {
        BankImportProfile excel = BankImportProfile.builder()
                .id(6L).name("BBVA pisos (Excel)").context(BankProfileContext.PROPIETARIOS)
                .headerRow(5).dateColumn(1).valueDateColumn(0).conceptColumn(2).conceptExtraColumn(3)
                .amountColumn(4).balanceColumn(6).dateFormat("dd/MM/yyyy").decimalComma(true)
                .build();
        List<List<String>> rows = List.of(
                List.of("", "", "", ""),
                List.of("", "", "Últimos movimientos"),
                List.of("", "", "Fecha de generación del informe: 02/10/2026"),
                List.of(""),
                List.of("F.Valor", "Fecha", "Concepto", "Movimiento", "Importe", "Divisa", "Disponible", "Divisa", "Observaciones"),
                List.of("02/09/2026", "02/09/2026", "Transferencia recibida", "Alquiler septiembre", "590", "EUR", "1369.82", "EUR", "Alquiler septiembre"),
                List.of("01/09/2026", "01/09/2026", "Transferencia realizada", "Traspaso mensual fulano", "-300", "EUR", "779.82", "EUR", "TRASPASO MENSUAL FULANO"),
                List.of("05/09/2026", "07/09/2026", "Restaurante", "Pago con tarjeta", "-190", "EUR", "1168.35", "EUR", "4188 RESTAURANTE"));

        List<BankStatementParser.Movement> movements = BankStatementParser.parse(excel, rows).movements();

        assertEquals(3, movements.size(), "los títulos de encima no son movimientos");
        assertEquals(new BigDecimal("590.00"), movements.get(0).amount(), "un número de Excel, aunque el perfil diga coma decimal");
        assertEquals(LocalDate.of(2026, 9, 2), movements.get(0).date());
        assertEquals("Transferencia recibida · Alquiler septiembre", movements.get(0).concept());
        assertEquals(new BigDecimal("-300.00"), movements.get(1).amount());
        assertEquals(new BigDecimal("779.82"), movements.get(1).balance());
        assertEquals(LocalDate.of(2026, 9, 7), movements.get(2).date(), "la fecha es la de la columna B");
        assertEquals(LocalDate.of(2026, 9, 5), movements.get(2).valueDate());
    }

    @Test
    void theSameTextInTwoColumnsIsNotRepeated() {
        BankImportProfile profile = BankImportProfile.builder()
                .id(7L).name("x").context(BankProfileContext.PROPIETARIOS)
                .headerRow(0).dateColumn(0).conceptColumn(1).conceptExtraColumn(2).amountColumn(3).decimalComma(true).build();
        List<BankStatementParser.Movement> movements = BankStatementParser.parse(profile, List.of(
                List.of("01/10/2026", "Traspaso mensual fulano", "TRASPASO MENSUAL FULANO", "-300"))).movements();
        assertEquals("Traspaso mensual fulano", movements.get(0).concept());
    }

    /**
     * Un cargo repartido con las proporciones de una regla: cada parte redondeada
     * al céntimo y la última con lo que quede, para que sumen justo el cargo.
     */
    @Test
    void aSplitAlwaysAddsUpToTheCharge() {
        com.storagemanager.storage_management.model.StorageUnit a = com.storagemanager.storage_management.model.StorageUnit.builder().id(1L).name("3º E").build();
        com.storagemanager.storage_management.model.StorageUnit b = com.storagemanager.storage_management.model.StorageUnit.builder().id(2L).name("3º I").build();
        com.storagemanager.storage_management.model.StorageUnit c = com.storagemanager.storage_management.model.StorageUnit.builder().id(3L).name("Bajo").build();
        com.storagemanager.storage_management.model.BankImportLine line = com.storagemanager.storage_management.model.BankImportLine.builder()
                .amount(new BigDecimal("-53.40")).build();

        com.storagemanager.storage_management.service.bank.BankMatcher.splitByShares(line, List.of(
                com.storagemanager.storage_management.model.BankMatchRuleSplit.builder().storageUnit(a).share(new BigDecimal("0.375")).build(),
                com.storagemanager.storage_management.model.BankMatchRuleSplit.builder().storageUnit(b).share(new BigDecimal("0.375")).build(),
                com.storagemanager.storage_management.model.BankMatchRuleSplit.builder().storageUnit(c).share(new BigDecimal("0.25")).build()));

        assertEquals(3, line.getSplits().size());
        assertEquals(new BigDecimal("20.03"), line.getSplits().get(0).getAmount());
        assertEquals(new BigDecimal("20.03"), line.getSplits().get(1).getAmount());
        assertEquals(new BigDecimal("13.34"), line.getSplits().get(2).getAmount(), "la última se lleva lo que quede");
        assertEquals(new BigDecimal("53.40"), line.getSplits().stream()
                .map(com.storagemanager.storage_management.model.BankImportLineSplit::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        assertNull(line.getStorageUnit(), "repartido, no va a una sola unidad");
    }

    /** Hay bancos que pegan la palabra al número: "préstamo3202659458". Tiene que reconocerse igual. */
    @Test
    void lettersAndDigitsStuckTogetherAreSeparated() {
        assertEquals("RCBO PRESTAMO 3202659458", TextMatch.normalize("rcbo. préstamo3202659458"));
        assertTrue(TextMatch.containsAny(TextMatch.normalize("rcbo. préstamo3202659458"), List.of("PRESTAMO")));
        assertEquals(List.of("8"), TextMatch.unitReferences("PAGO TRASTERO8 OCTUBRE"));
    }

    /**
     * El Excel de la otra cuenta de los pisos: titular e IBAN encima, cabecera en
     * la fila 4, fechas de Excel (llegan como yyyy-mm-dd) y un número de apunte
     * correlativo que sirve de identificador del movimiento.
     */
    @Test
    void readsAnExportWithASequentialEntryNumber() {
        BankImportProfile profile = BankImportProfile.builder()
                .id(8L).name("Otra cuenta de los pisos").context(BankProfileContext.PROPIETARIOS)
                .headerRow(4).dateColumn(0).valueDateColumn(1).conceptColumn(2).amountColumn(3)
                .balanceColumn(4).referenceColumn(5).decimalComma(true)
                .build();
        List<List<String>> rows = List.of(
                List.of("Nombre", "Fulano de Tal", "", "", "", ""),
                List.of("IBAN", "ES00 0000 0000 0000 0000 0000", "", "", "", ""),
                List.of("", "", "", "", "", ""),
                List.of("Fecha de la operación", "Fecha valor", "Tipo movimiento", "Importe", "Saldo", "Nro. Apunte"),
                List.of("2026-09-10", "2026-09-10", "rcbo. préstamo3202659458", "-277.71", "2245.03", "130"),
                List.of("2026-09-12", "2026-09-14", "trf. cuenta bbva", "-2000", "245.03", "131"));

        List<BankStatementParser.Movement> movements = BankStatementParser.parse(profile, rows).movements();

        assertEquals(2, movements.size(), "el titular y el IBAN de encima no son movimientos");
        assertEquals(LocalDate.of(2026, 9, 10), movements.get(0).date());
        assertEquals(new BigDecimal("-277.71"), movements.get(0).amount());
        assertEquals("130", movements.get(0).reference());
        assertEquals(LocalDate.of(2026, 9, 14), movements.get(1).valueDate());
    }

    /** Lo que de verdad escribe quien paga un trastero: el número, no su nombre. */
    @Test
    void unitNumbersAreFoundInWhatPeopleWrite() {
        assertEquals(List.of("8"), TextMatch.unitReferences("TRANSFERENCIAS · OCTUBRE 2026 TRASTERO 8"));
        assertEquals(List.of("9"), TextMatch.unitReferences("BAIXO TRASTEIRO 9 PASAXE"));
        assertEquals(List.of("4"), TextMatch.unitReferences("trastero n.mero 4"), "la tilde que se comió el banco");
        assertEquals(List.of("4"), TextMatch.unitReferences("pago de trastero número 4"));
        assertEquals(List.of("5"), TextMatch.unitReferences("TRASTERO NUMERO 5 PONTE PASAX"));
        assertEquals(List.of("7"), TextMatch.unitReferences("Baixo 7"));
        assertEquals(List.of("7"), TextMatch.unitReferences("Trasteiro 7 Fulano de Tal"));
        assertEquals(List.of(), TextMatch.unitReferences("TRASTERO MES AGOSTO"));
        assertEquals(List.of(), TextMatch.unitReferences("N 2026267000081741 IGNIS LUZ"));
    }

    /**
     * "TRASTERO MES AGOSTO" no dice quién paga: una regla hecha con eso atraparía
     * los pagos de todos los trasteros. No se aprende.
     */
    @Test
    void aConceptWithOnlyGenericWordsTeachesNothing() {
        assertEquals("", TextMatch.learnKey("TRANSFERENCIAS · TRASTERO MES AGOSTO"));
        assertEquals("", TextMatch.learnKey("TRANSFERENCIAS · OCTUBRE 2026 TRASTERO 8"));
        assertEquals("JUAN MAR", TextMatch.learnKey("TRANSFERENCIAS · juan Mar.a"));
    }

    @Test
    void monthsAndYearAreReadFromTheConcept() {
        assertEquals(List.of(9, 10), TextMatch.monthsIn("Mes de septiembre y octubre trastero 6 Av Pasaxe"));
        assertEquals(List.of(10), TextMatch.monthsIn("OCTUBRE 2026 TRASTERO 8"));
        assertEquals(List.of(7), TextMatch.monthsIn("Mensualidade de xullo"), "también en gallego");
        assertEquals(2026, TextMatch.yearIn("OCTUBRE 2026 TRASTERO 8"));
        assertNull(TextMatch.yearIn("TRASTERO MES AGOSTO"));
    }

    @Test
    void readsSeparateDebitAndCreditColumns() {
        // Como muchos csv: sin títulos, cargo y abono aparte, el cargo en positivo.
        BankImportProfile profile = BankImportProfile.builder()
                .id(2L).name("Comunidad").context(BankProfileContext.COMUNIDAD)
                .headerRow(1).dateColumn(0).conceptColumn(1).debitColumn(2).creditColumn(3)
                .dateFormat("dd-MM-yy").decimalComma(true)
                .build();
        List<List<String>> rows = List.of(
                List.of("Fecha", "Concepto", "Cargo", "Abono"),
                List.of("03-09-26", "CUOTA 3 IZDA", "", "60,00"),
                List.of("10-09-26", "ASCENSORES SA", "1.210,00", ""));

        List<BankStatementParser.Movement> movements = BankStatementParser.parse(profile, rows).movements();

        assertEquals(2, movements.size());
        assertEquals(new BigDecimal("60.00"), movements.get(0).amount());
        assertEquals(new BigDecimal("-1210.00"), movements.get(1).amount(), "1.210,00 son mil doscientos diez");
        assertEquals(LocalDate.of(2026, 9, 10), movements.get(1).date());
    }

    @Test
    void reportsRowsThatLookLikeMovementsButCannotBeRead() {
        List<List<String>> rows = List.of(
                List.of("Fecha", "Concepto", "Importe"),
                List.of("ayer", "ALGO", "10,00"));
        BankImportProfile profile = BankImportProfile.builder()
                .id(3L).name("x").context(BankProfileContext.PROPIETARIOS)
                .headerRow(1).dateColumn(0).conceptColumn(1).amountColumn(2).decimalComma(true).build();

        BankStatementParser.Result result = BankStatementParser.parse(profile, rows);

        assertTrue(result.movements().isEmpty());
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("Fila 2"), result.warnings().toString());
    }

    @Test
    void theSameMovementHasTheSameFingerprint() {
        BankStatementParser.Movement a = new BankStatementParser.Movement(6, LocalDate.of(2026, 9, 2), null,
                "Transferencia de Gómez Pérez, Ana", new BigDecimal("590.00"), new BigDecimal("1590.00"));
        BankStatementParser.Movement sameInOtherFile = new BankStatementParser.Movement(9, LocalDate.of(2026, 9, 2), null,
                "TRANSFERENCIA DE GOMEZ PEREZ ANA", new BigDecimal("590.00"), new BigDecimal("1590.00"));
        BankStatementParser.Movement anotherDayOrBalance = new BankStatementParser.Movement(7, LocalDate.of(2026, 9, 2), null,
                "TRANSFERENCIA DE GOMEZ PEREZ ANA", new BigDecimal("590.00"), new BigDecimal("2180.00"));

        assertEquals(BankStatementParser.fingerprint(1L, a), BankStatementParser.fingerprint(1L, sameInOtherFile),
                "la fila del fichero y las tildes no cambian el movimiento");
        assertNotEquals(BankStatementParser.fingerprint(1L, a), BankStatementParser.fingerprint(1L, anotherDayOrBalance),
                "dos cobros iguales del mismo día son dos: el saldo los distingue");
        assertNotEquals(BankStatementParser.fingerprint(1L, a), BankStatementParser.fingerprint(2L, a),
                "el mismo importe en otra cuenta es otro movimiento");
    }

    @Test
    void namesAreRecognisedInAnyOrderAndWithoutAccents() {
        Set<String> concept = TextMatch.words("TRANSFERENCIA INMEDIATA DE GOMEZ PEREZ ANA CONCEPTO ALQUILER OCTUBRE");
        assertTrue(TextMatch.nameScore("Ana Gómez Pérez", concept) >= 2);
        assertEquals(0, TextMatch.nameScore("Ana Rodríguez Souto", concept), "con una sola palabra no basta");
        assertEquals(0, TextMatch.nameScore("Luis de la Fuente", TextMatch.words("PAGO DE LA LUZ")),
                "las partículas no cuentan");
    }

    @Test
    void aLearntRuleIgnoresMonthsAndNumbers() {
        String key = TextMatch.learnKey("TRANSFERENCIA DE PEREZ SOUTO CARMEN ALQUILER 3 IZDA OCTUBRE 2026");
        assertEquals("PEREZ SOUTO CARMEN ALQUILER IZDA", key);
        assertTrue(TextMatch.matchesRule(key,
                TextMatch.words("TRANSF. DE PÉREZ SOUTO, CARMEN - ALQUILER 3º IZDA NOVIEMBRE 2026")),
                "el mes siguiente tiene que reconocerse igual");
    }
}
