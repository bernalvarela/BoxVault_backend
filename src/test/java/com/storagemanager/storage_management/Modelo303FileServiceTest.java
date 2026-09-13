package com.storagemanager.storage_management;

import com.storagemanager.storage_management.dto.Modelo303DTO;
import com.storagemanager.storage_management.model.Owner;
import com.storagemanager.storage_management.model.enums.OwnerType;
import com.storagemanager.storage_management.repository.OwnerRepository;
import com.storagemanager.storage_management.service.Modelo303FileService;
import com.storagemanager.storage_management.service.TaxService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * El fichero del 303 se importa por posiciones: lo que importa es que cada página
 * mida lo que dice el diseño de registro de la AEAT y que las casillas caigan donde
 * toca. Estas comprobaciones recorren el fichero con los offsets del DR303.
 */
class Modelo303FileServiceTest {

    private static final int HEADER = 328;
    private static final int PAGE_1 = 1581;
    private static final int PAGE_3 = 1017;
    private static final int PAGE_DID = 823;
    private static final int CLOSING = 18;

    /** Campo del diseño de registro: posición de 1 en adelante, como en el DR. */
    private static String field(String page, int position, int length) {
        return page.substring(position - 1, position - 1 + length);
    }

    private static Modelo303DTO reportWith(BigDecimal base, BigDecimal vat) {
        return reportWith(base, vat, BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));
    }

    /** Un informe donde sólo el 1T tiene cifras. */
    private static Modelo303DTO reportWith(BigDecimal base, BigDecimal vat,
                                           BigDecimal deductibleBase, BigDecimal deductibleVat) {
        List<Modelo303DTO.Quarter> quarters = new ArrayList<>();
        for (int q = 1; q <= 4; q++) {
            BigDecimal zero = BigDecimal.ZERO.setScale(2);
            BigDecimal qBase = q == 1 ? base : zero;
            BigDecimal qVat = q == 1 ? vat : zero;
            quarters.add(Modelo303DTO.Quarter.builder()
                    .quarter(q)
                    .label(q + "T 2026")
                    .collectedBase(qBase).collectedVat(qVat).collectedTotal(qBase.add(qVat))
                    .expectedBase(qBase).expectedVat(qVat).expectedTotal(qBase.add(qVat))
                    .deductibleBase(q == 1 ? deductibleBase : zero)
                    .deductibleVat(q == 1 ? deductibleVat : zero)
                    .build());
        }
        return Modelo303DTO.builder().year(2026).quarters(quarters).build();
    }

    private static Modelo303FileService serviceReturning(Modelo303DTO report, Owner declarant) {
        TaxService taxService = mock(TaxService.class);
        when(taxService.modelo303(anyInt(), any())).thenReturn(report);
        OwnerRepository owners = mock(OwnerRepository.class);
        when(owners.findById(declarant.getId())).thenReturn(Optional.of(declarant));
        return new Modelo303FileService(taxService, owners);
    }

    private static Owner entity() {
        return Owner.builder()
                .id(7L)
                .fullName("Comunidad de bienes Pasaxe 29")
                .type(OwnerType.COMUNIDAD_DE_BIENES)
                .documentId("E36123456")
                .build();
    }

    @Test
    void fileFollowsTheAeatRecordDesign() {
        Modelo303FileService service = serviceReturning(
                reportWith(new BigDecimal("1000.00"), new BigDecimal("210.00")), entity());

        Modelo303FileService.Modelo303File file =
                service.generate(2026, 1, 7L, Modelo303FileService.Options.defaults());

        assertEquals("303-2026-1T.303", file.fileName());
        String content = file.content();
        assertEquals(HEADER + PAGE_1 + PAGE_3 + PAGE_DID + CLOSING, content.length());
        assertFalse(content.contains("\n"), "el fichero es un único registro, sin saltos de línea");

        // Cabecera: <T + 303 + discriminante + ejercicio + período + tipo y cierre, y el bloque <AUX>
        assertTrue(content.startsWith("<T303020261T0000><AUX>"), content.substring(0, 40));
        assertEquals("</AUX>", field(content, 323, 6));
        assertTrue(content.endsWith("</T303020261T0000>"));

        String page1 = content.substring(HEADER, HEADER + PAGE_1);
        assertTrue(page1.startsWith("<T30301000>"));
        assertTrue(page1.endsWith("</T30301000>"));
        assertEquals("I", field(page1, 13, 1)); // a ingresar
        assertEquals("E36123456", field(page1, 14, 9));
        assertEquals("COMUNIDAD DE BIENES PASAXE 29", field(page1, 23, 29).trim());
        assertEquals("2026", field(page1, 103, 4));
        assertEquals("1T", field(page1, 107, 2));
        // Régimen general al 21 %: base [07], tipo [08] y cuota [09]
        assertEquals("00000000000100000", field(page1, 326, 17));
        assertEquals("02100", field(page1, 343, 5));
        assertEquals("00000000000021000", field(page1, 348, 17));
        assertEquals("00000000000021000", field(page1, 696, 17));  // [27] total cuota devengada
        assertEquals("00000000000000000", field(page1, 1002, 17)); // [45] total a deducir
        assertEquals("00000000000021000", field(page1, 1019, 17)); // [46] resultado régimen general

        String page3 = content.substring(HEADER + PAGE_1, HEADER + PAGE_1 + PAGE_3);
        assertTrue(page3.startsWith("<T30303000>"));
        assertTrue(page3.endsWith("</T30303000>"));
        assertEquals("00000000000021000", field(page3, 199, 17)); // [64] suma de resultados
        assertEquals("10000", field(page3, 216, 5));              // [65] 100 % al Estado
        assertEquals("00000000000021000", field(page3, 221, 17)); // [66] atribuible al Estado
        assertEquals("00000000000021000", field(page3, 340, 17)); // [69] resultado de la autoliquidación
        assertEquals("00000000000021000", field(page3, 408, 17)); // [71] resultado
        assertEquals(" ", field(page3, 425, 1));                  // hubo actividad

        String did = content.substring(HEADER + PAGE_1 + PAGE_3, HEADER + PAGE_1 + PAGE_3 + PAGE_DID);
        assertTrue(did.startsWith("<T303DID00>"));
        assertTrue(did.endsWith("</T303DID00>"));
    }

    @Test
    void deductibleVatOfTheExpensesLowersTheQuota() {
        Modelo303FileService service = serviceReturning(
                reportWith(new BigDecimal("1000.00"), new BigDecimal("210.00"),
                        new BigDecimal("400.00"), new BigDecimal("84.00")), entity());

        String content = service.generate(2026, 1, 7L, Modelo303FileService.Options.defaults()).content();
        String page1 = content.substring(HEADER, HEADER + PAGE_1);
        String page3 = content.substring(HEADER + PAGE_1, HEADER + PAGE_1 + PAGE_3);

        assertEquals("00000000000040000", field(page1, 713, 17));  // [28] base soportada
        assertEquals("00000000000008400", field(page1, 730, 17));  // [29] cuota soportada
        assertEquals("00000000000000000", field(page1, 747, 17));  // [30] bienes de inversión, sin datos
        assertEquals("00000000000008400", field(page1, 1002, 17)); // [45] total a deducir
        assertEquals("00000000000012600", field(page1, 1019, 17)); // [46] 210 - 84
        assertEquals("00000000000012600", field(page3, 408, 17));  // [71] resultado
    }

    @Test
    void aQuarterThatEndsInFavourOfTheTaxpayerIsFiledToOffset() {
        Modelo303FileService service = serviceReturning(
                reportWith(new BigDecimal("100.00"), new BigDecimal("21.00"),
                        new BigDecimal("400.00"), new BigDecimal("84.00")), entity());

        String content = service.generate(2026, 1, 7L, Modelo303FileService.Options.defaults()).content();
        String page1 = content.substring(HEADER, HEADER + PAGE_1);
        String page3 = content.substring(HEADER + PAGE_1, HEADER + PAGE_1 + PAGE_3);

        assertEquals("C", field(page1, 13, 1));                    // a compensar: no es el 4T
        assertEquals("N0000000000006300", field(page1, 1019, 17)); // [46] 21 - 84, negativo
        assertEquals("N0000000000006300", field(page3, 408, 17));  // [71] con la N del signo
    }

    @Test
    void quarterWithoutIncomeIsFiledAsNoActivity() {
        BigDecimal zero = BigDecimal.ZERO.setScale(2);
        Modelo303FileService service = serviceReturning(reportWith(zero, zero), entity());

        String content = service.generate(2026, 1, 7L, Modelo303FileService.Options.defaults()).content();
        String page1 = content.substring(HEADER, HEADER + PAGE_1);
        String page3 = content.substring(HEADER + PAGE_1, HEADER + PAGE_1 + PAGE_3);

        assertEquals("N", field(page1, 13, 1));   // sin resultado
        assertEquals("X", field(page3, 425, 1));  // declaración sin actividad
    }

    @Test
    void offsettingPreviousQuartersLowersTheResult() {
        Modelo303FileService service = serviceReturning(
                reportWith(new BigDecimal("1000.00"), new BigDecimal("210.00")), entity());

        Modelo303FileService.Options options = new Modelo303FileService.Options(
                Modelo303FileService.Basis.COLLECTED,
                new BigDecimal("50.00"), new BigDecimal("50.00"), false);
        String content = service.generate(2026, 1, 7L, options).content();
        String page3 = content.substring(HEADER + PAGE_1, HEADER + PAGE_1 + PAGE_3);

        assertEquals("00000000000005000", field(page3, 255, 17)); // [110] pendientes de periodos anteriores
        assertEquals("00000000000005000", field(page3, 272, 17)); // [78] aplicadas en este periodo
        assertEquals("00000000000000000", field(page3, 289, 17)); // [87] pendientes para periodos posteriores
        assertEquals("00000000000016000", field(page3, 408, 17)); // [71] 210 - 50
    }

    @Test
    void directDebitFilesTheAccountOfTheEntity() {
        Owner withAccount = entity();
        withAccount.setBankAccount("ES91 2100 0418 4502 0005 1332");
        Modelo303FileService service = serviceReturning(
                reportWith(new BigDecimal("1000.00"), new BigDecimal("210.00")), withAccount);

        Modelo303FileService.Options options = new Modelo303FileService.Options(
                Modelo303FileService.Basis.COLLECTED, BigDecimal.ZERO, BigDecimal.ZERO, true);
        String content = service.generate(2026, 1, 7L, options).content();
        String page1 = content.substring(HEADER, HEADER + PAGE_1);
        String did = content.substring(HEADER + PAGE_1 + PAGE_3, HEADER + PAGE_1 + PAGE_3 + PAGE_DID);

        assertEquals("U", field(page1, 13, 1));                          // domiciliado
        assertEquals("ES9121000418450200051332", field(did, 23, 24));    // IBAN, sin espacios
    }

    @Test
    void directDebitWithoutAnAccountIsRejected() {
        Modelo303FileService service = serviceReturning(
                reportWith(new BigDecimal("1000.00"), new BigDecimal("210.00")), entity());

        Modelo303FileService.Options options = new Modelo303FileService.Options(
                Modelo303FileService.Basis.COLLECTED, BigDecimal.ZERO, BigDecimal.ZERO, true);
        assertThrows(RuntimeException.class, () -> service.generate(2026, 1, 7L, options));
    }

    @Test
    void aDeclarantWithoutNifIsRejected() {
        Owner withoutNif = entity();
        withoutNif.setDocumentId(null);
        Modelo303FileService service = serviceReturning(
                reportWith(new BigDecimal("1000.00"), new BigDecimal("210.00")), withoutNif);

        assertThrows(RuntimeException.class,
                () -> service.generate(2026, 1, 7L, Modelo303FileService.Options.defaults()));
    }
}
