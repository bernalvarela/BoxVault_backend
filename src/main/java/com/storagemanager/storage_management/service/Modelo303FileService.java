package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.dto.Modelo303DTO;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.Owner;
import com.storagemanager.storage_management.model.enums.OwnerType;
import com.storagemanager.storage_management.repository.OwnerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.List;

/**
 * Genera el fichero de importación del Modelo 303 (IVA) que admite el formulario
 * web de la Agencia Tributaria ("Importar" en la Sede electrónica).
 * <p>
 * Sigue el diseño de registro oficial DR303 v1.01 (presentaciones del ejercicio
 * 2026 en adelante): un único registro continuo, sin saltos de línea, formado por
 * la cabecera {@code <T3030 ejercicio periodo 0000>}, el bloque {@code <AUX>},
 * la página 1 (identificación y liquidación del régimen general, 1581 posiciones),
 * la página 3 (información adicional y resultado, 1017 posiciones), la página DID
 * (cuenta bancaria, 823 posiciones) y la marca de cierre {@code </T3030…0000>}.
 * Las páginas 2 (régimen simplificado), 4 (exonerados del 390) y 5 (prorratas) no
 * se incluyen porque no aplican al alquiler de trasteros.
 * <p>
 * Sólo se rellena lo que BoxVault conoce: el IVA repercutido al 21 % de las
 * mensualidades del trimestre (casillas [07], [08], [09], [27]). El IVA soportado
 * ([28]…[45]) va a cero, porque los gastos no guardan cuota de IVA: si hay IVA
 * deducible hay que añadirlo en el formulario de la AEAT después de importar.
 * El fichero es un borrador para importar y revisar, nunca una presentación.
 */
@Service
@RequiredArgsConstructor
public class Modelo303FileService {

    /** Longitudes del diseño de registro, para comprobar lo que se escribe. */
    private static final int PAGE_1_LENGTH = 1581;
    private static final int PAGE_3_LENGTH = 1017;
    private static final int PAGE_DID_LENGTH = 823;
    private static final int HEADER_LENGTH = 328;

    /** Tipo impositivo general, en centésimas de punto: 21,00 %. */
    private static final String VAT_RATE_21 = "02100";
    /** [65] % atribuible a la Administración del Estado: 100,00 %. */
    private static final String STATE_PERCENT = "10000";

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final TaxService taxService;
    private final OwnerRepository ownerRepository;

    /** Base sobre la que se declara: lo cobrado en el trimestre o todo lo devengado. */
    public enum Basis { COLLECTED, EXPECTED }

    /**
     * Opciones que no se pueden deducir de los datos guardados y que el declarante
     * decide al presentar.
     *
     * @param basis                base de cálculo (por defecto, lo cobrado: es lo que muestra la vista de impuestos)
     * @param pendingToOffset      [110] cuotas a compensar pendientes de periodos anteriores
     * @param offsetApplied        [78] cuotas a compensar de periodos anteriores aplicadas en este periodo
     * @param directDebit          domiciliar el ingreso en la cuenta del titular (tipo de declaración "U")
     */
    public record Options(Basis basis, BigDecimal pendingToOffset, BigDecimal offsetApplied, boolean directDebit) {
        public static Options defaults() {
            return new Options(Basis.COLLECTED, ZERO, ZERO, false);
        }
    }

    /** Fichero generado: el nombre sugerido y su contenido (ISO-8859-1 al descargarlo). */
    public record Modelo303File(String fileName, String content) {}

    public Modelo303File generate(int year, int quarter, Long ownerId, Options options) {
        if (quarter < 1 || quarter > 4) {
            throw new BadRequestException("El trimestre debe estar entre 1 y 4");
        }
        Owner declarant = declarant(ownerId);
        String nif = normalize(declarant.getDocumentId());
        if (nif.isBlank()) {
            throw new BadRequestException("La comunidad de bienes " + declarant.getFullName()
                    + " no tiene NIF: rellénalo en su ficha antes de generar el fichero del 303");
        }

        Modelo303DTO report = taxService.modelo303(year, declarant.getId());
        Modelo303DTO.Quarter q = report.getQuarters().stream()
                .filter(x -> x.getQuarter() == quarter)
                .findFirst()
                .orElseThrow(() -> new BadRequestException("Sin datos del " + quarter + "T " + year));

        boolean collected = options.basis() != Basis.EXPECTED;
        BigDecimal base = money(collected ? q.getCollectedBase() : q.getExpectedBase());
        BigDecimal vat = money(collected ? q.getCollectedVat() : q.getExpectedVat());

        // [45] va a cero mientras los gastos no guarden IVA soportado, así que el
        // resultado del régimen general [46] es toda la cuota devengada [27].
        BigDecimal deductible = ZERO;
        BigDecimal result46 = vat.subtract(deductible);
        BigDecimal pending110 = money(options.pendingToOffset());
        BigDecimal applied78 = money(options.offsetApplied());
        BigDecimal remaining87 = pending110.subtract(applied78).max(ZERO);
        BigDecimal result69 = result46.subtract(applied78);
        BigDecimal result71 = result69;

        String period = quarter + "T";
        char declarationType = declarationType(result71, quarter, options.directDebit());
        String iban = normalize(declarant.getBankAccount()).replace(" ", "");

        String content = header(year, period)
                + page1(nif, declarant.getFullName(), year, period, declarationType, base, vat, result46)
                + page3(result46, result69, result71, pending110, applied78, remaining87,
                        base.signum() == 0 && vat.signum() == 0)
                + pageDid(declarationType, iban)
                + "</T3030" + year + period + "0000>";

        return new Modelo303File("303-" + year + "-" + period + ".303", content);
    }

    /**
     * Tipo de declaración: N sin resultado, I a ingresar (U si se domicilia),
     * D a devolver (sólo el 4T) y C a compensar en el resto de trimestres.
     */
    private static char declarationType(BigDecimal result, int quarter, boolean directDebit) {
        int sign = result.compareTo(BigDecimal.ZERO);
        if (sign == 0) return 'N';
        if (sign > 0) return directDebit ? 'U' : 'I';
        return quarter == 4 ? 'D' : 'C';
    }

    private Owner declarant(Long ownerId) {
        if (ownerId != null) {
            return ownerRepository.findById(ownerId)
                    .orElseThrow(() -> new ResourceNotFoundException("Owner not found with id: " + ownerId));
        }
        List<Owner> entities = ownerRepository.findByTypeOrderByFullNameAsc(OwnerType.COMUNIDAD_DE_BIENES);
        if (entities.isEmpty()) {
            throw new BadRequestException("No hay ninguna comunidad de bienes registrada que presente el 303");
        }
        return entities.get(0);
    }

    // ------------------------------------------------------------------
    // Registros
    // ------------------------------------------------------------------

    /** Cabecera del fichero: identificación de la declaración y bloque {@code <AUX>}. */
    private static String header(int year, String period) {
        Rec r = new Rec();
        r.raw("<T");                 // 1-2   constante
        r.raw("303");                // 3-5   modelo
        r.raw("0");                  // 6     discriminante
        r.raw(String.valueOf(year)); // 7-10  ejercicio de devengo
        r.raw(period);               // 11-12 período
        r.raw("0000>");              // 13-17 tipo y cierre del identificador
        r.raw("<AUX>");              // 18-22
        r.blanks(70);                // 23-92   reservado
        r.an("BOXV", 4);             // 93-96   versión del programa
        r.blanks(4);                 // 97-100  reservado
        r.blanks(9);                 // 101-109 NIF de la empresa de desarrollo
        r.blanks(213);               // 110-322 reservado
        r.raw("</AUX>");             // 323-328
        return r.done(HEADER_LENGTH);
    }

    /** Página 1: identificación, devengo e IVA devengado / deducible del régimen general. */
    private static String page1(String nif, String name, int year, String period, char declarationType,
                                BigDecimal base, BigDecimal vat, BigDecimal result46) {
        Rec r = new Rec();
        r.raw("<T303");
        r.raw("01000");
        r.raw(">");
        r.blanks(1);                        // 12  página complementaria
        r.raw(String.valueOf(declarationType)); // 13 tipo de declaración
        r.an(nif, 9);                       // 14-22
        r.an(name, 80);                     // 23-102
        r.raw(String.valueOf(year));        // 103-106 ejercicio
        r.raw(period);                      // 107-108 período
        r.raw("2");   // 109 tributación exclusivamente foral: NO
        r.raw("2");   // 110 registro de devolución mensual: NO
        r.raw("3");   // 111 tributa exclusivamente en régimen simplificado: NO (sólo régimen general)
        r.raw("2");   // 112 autoliquidación conjunta: NO
        r.raw("2");   // 113 régimen especial del criterio de caja: NO
        r.raw("2");   // 114 destinatario de operaciones con criterio de caja: NO
        r.raw("2");   // 115 opción por la prorrata especial: NO
        r.raw("2");   // 116 revocación de la prorrata especial: NO
        r.raw("2");   // 117 concurso de acreedores en el período: NO
        r.blanks(8);  // 118-125 fecha del auto de concurso
        r.blanks(1);  // 126 tipo de autoliquidación por concurso
        r.raw("2");   // 127 acogido voluntariamente al SII: NO
        r.raw("0");   // 128 exonerado del modelo 390: no procede en 1T-3T; en el 4T lo marca el declarante
        r.raw("0");   // 129 volumen anual de operaciones distinto de cero
        r.raw("0");   // 130 deducción del pago a cuenta de gasolinas: NO

        // IVA devengado. Sólo hay operaciones al 21 %: [07] base, [08] tipo y [09] cuota.
        r.amount(ZERO); r.raw("00000"); r.amount(ZERO);   // [150] [151] [152]  0 %
        r.amount(ZERO); r.raw("00000"); r.amount(ZERO);   // [165] [166] [167]  2 %
        r.amount(ZERO); r.raw("00400"); r.amount(ZERO);   // [01]  [02]  [03]   4 %
        r.amount(ZERO); r.raw("00000"); r.amount(ZERO);   // [153] [154] [155]  5 % / 7,5 %
        r.amount(ZERO); r.raw("01000"); r.amount(ZERO);   // [04]  [05]  [06]  10 %
        r.amount(base); r.raw(VAT_RATE_21); r.amount(vat); // [07] [08] [09]   21 %
        r.amount(ZERO); r.amount(ZERO);                   // [10] [11] adquisiciones intracomunitarias
        r.amount(ZERO); r.amount(ZERO);                   // [12] [13] inversión del sujeto pasivo
        r.signed(ZERO); r.signed(ZERO);                   // [14] [15] modificación de bases y cuotas
        r.amount(ZERO); r.raw("00175"); r.amount(ZERO);   // [156] [157] [158] recargo 1,75 %
        r.amount(ZERO); r.raw("00050"); r.amount(ZERO);   // [168] [169] [170] recargo 0,5 %
        r.amount(ZERO); r.raw("00000"); r.amount(ZERO);   // [16]  [17]  [18]  recargo 0 % / 0,5 % / 0,62 % / 1 %
        r.amount(ZERO); r.raw("00140"); r.amount(ZERO);   // [19]  [20]  [21]  recargo 1,4 %
        r.amount(ZERO); r.raw("00520"); r.amount(ZERO);   // [22]  [23]  [24]  recargo 5,2 %
        r.signed(ZERO); r.signed(ZERO);                   // [25] [26] modificación del recargo
        r.signed(vat);                                    // [27] total cuota devengada

        // IVA deducible: BoxVault no guarda cuotas soportadas, todo va a cero.
        for (int i = 0; i < 12; i++) r.amount(ZERO);      // [28]…[39] bases y cuotas soportadas
        r.signed(ZERO); r.signed(ZERO);                   // [40] [41] rectificación de deducciones
        r.signed(ZERO);                                   // [42] compensaciones REAGP
        r.signed(ZERO);                                   // [43] regularización de inversiones
        r.signed(ZERO);                                   // [44] regularización de prorrata
        r.signed(ZERO);                                   // [45] total a deducir
        r.signed(result46);                               // [46] resultado del régimen general

        r.blanks(521);   // 1036-1556 reservado para la AEAT
        r.blanks(13);    // 1557-1569 sello electrónico
        r.raw("</T30301000>");
        return r.done(PAGE_1_LENGTH);
    }

    /** Página 3: información adicional y resultado de la autoliquidación. */
    private static String page3(BigDecimal result46, BigDecimal result69, BigDecimal result71,
                                BigDecimal pending110, BigDecimal applied78, BigDecimal remaining87,
                                boolean noActivity) {
        Rec r = new Rec();
        r.raw("<T303");
        r.raw("03000");
        r.raw(">");
        r.signed(ZERO);   // [59]  entregas intracomunitarias
        r.signed(ZERO);   // [60]  exportaciones
        r.signed(ZERO);   // [120] no sujetas por reglas de localización
        r.signed(ZERO);   // [122] sujetas con inversión del sujeto pasivo
        r.signed(ZERO);   // [123] ventanilla única, no sujetas
        r.signed(ZERO);   // [124] ventanilla única, sujetas
        r.signed(ZERO);   // [62]  criterio de caja, base
        r.signed(ZERO);   // [63]  criterio de caja, cuota
        r.signed(ZERO);   // [74]  adquisiciones con criterio de caja, base
        r.signed(ZERO);   // [75]  adquisiciones con criterio de caja, cuota
        r.signed(ZERO);   // [76]  regularización art. 80.cinco.5ª LIVA
        r.signed(result46);   // [64] suma de resultados
        r.raw(STATE_PERCENT); // [65] % atribuible al Estado
        r.signed(result46);   // [66] atribuible al Estado
        r.amount(ZERO);       // [77] IVA a la importación pendiente de ingreso
        r.amount(pending110); // [110] cuotas a compensar pendientes de periodos anteriores
        r.amount(applied78);  // [78]  aplicadas en este periodo
        r.amount(remaining87);// [87]  pendientes para periodos posteriores
        r.signed(ZERO);       // [68] regularización anual (tributación conjunta con forales)
        r.signed(ZERO);       // [108] otros ajustes de la rectificativa
        r.signed(result69);   // [69] resultado de la autoliquidación
        r.amount(ZERO);       // [70] resultados a ingresar de autoliquidaciones anteriores
        r.amount(ZERO);       // [109] devoluciones acordadas por la AEAT
        r.amount(ZERO);       // [112] pago a cuenta de gasolinas
        r.signed(result71);   // [71] resultado
        r.raw(noActivity ? "X" : " "); // declaración sin actividad
        r.blanks(1);          // autoliquidación rectificativa
        r.blanks(13);         // número de justificante de la autoliquidación anterior
        r.blanks(1);          // baja / modificación de la domiciliación
        r.amount(ZERO);       // [111] importe de la rectificación
        r.blanks(1);          // motivo: rectificaciones
        r.blanks(1);          // motivo: discrepancia de criterio administrativo
        r.blanks(546);        // reservado para la AEAT
        r.raw("</T30303000>");
        return r.done(PAGE_3_LENGTH);
    }

    /** Página DID: cuenta bancaria de la domiciliación o de la devolución. */
    private static String pageDid(char declarationType, String iban) {
        boolean needsAccount = declarationType == 'U' || declarationType == 'D';
        Rec r = new Rec();
        r.raw("<T303");
        r.raw("DID00");
        r.raw(">");
        r.blanks(11);                                   // SWIFT-BIC (sólo devolución al extranjero)
        r.an(needsAccount ? iban : "", 34);             // IBAN
        r.blanks(70);                                   // nombre del banco
        r.blanks(35);                                   // dirección del banco
        r.blanks(30);                                   // ciudad
        r.blanks(2);                                    // código de país
        r.raw(declarationType == 'D' ? "1" : "0");      // marca SEPA: cuenta de España
        r.blanks(617);                                  // reservado para la AEAT
        r.raw("</T303DID00>");
        return r.done(PAGE_DID_LENGTH);
    }

    // ------------------------------------------------------------------
    // Escritura de campos
    // ------------------------------------------------------------------

    private static BigDecimal money(BigDecimal value) {
        return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    /** Deja sólo letras, números y blancos, que es lo único que admiten los campos alfanuméricos. */
    private static String normalize(String value) {
        if (value == null) return "";
        String ascii = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return ascii.toUpperCase().replaceAll("[^A-Z0-9 ]", " ").trim();
    }

    /** Construye un registro comprobando que cada campo ocupa lo que dice el diseño. */
    private static final class Rec {
        private final StringBuilder sb = new StringBuilder();

        /** Constantes y campos ya formateados. */
        void raw(String value) {
            sb.append(value);
        }

        void blanks(int length) {
            sb.append(" ".repeat(length));
        }

        /** Alfanumérico: alineado a la izquierda y relleno con blancos por la derecha. */
        void an(String value, int length) {
            String v = normalize(value);
            if (v.length() > length) v = v.substring(0, length);
            sb.append(v).append(" ".repeat(length - v.length()));
        }

        /** Importe de 17 posiciones sin signo (dos decimales implícitos). */
        void amount(BigDecimal value) {
            sb.append(cents(value, 17));
        }

        /** Importe de 17 posiciones con signo: una "N" delante cuando es negativo. */
        void signed(BigDecimal value) {
            if (value.signum() < 0) {
                sb.append("N").append(cents(value.abs(), 16));
            } else {
                sb.append(cents(value, 17));
            }
        }

        private static String cents(BigDecimal value, int length) {
            String digits = value.setScale(2, RoundingMode.HALF_UP).movePointRight(2).toBigInteger().toString();
            if (digits.length() > length) {
                throw new BadRequestException("Importe demasiado grande para el fichero del 303: " + value);
            }
            return "0".repeat(length - digits.length()) + digits;
        }

        String done(int expectedLength) {
            if (sb.length() != expectedLength) {
                throw new IllegalStateException("Registro del modelo 303 de " + sb.length()
                        + " posiciones, se esperaban " + expectedLength);
            }
            return sb.toString();
        }
    }
}
