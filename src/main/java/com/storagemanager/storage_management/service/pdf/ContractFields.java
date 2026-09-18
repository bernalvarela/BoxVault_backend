package com.storagemanager.storage_management.service.pdf;

import com.storagemanager.storage_management.config.VatUtils;
import com.storagemanager.storage_management.model.Client;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.model.enums.UnitKind;
import com.storagemanager.storage_management.service.InvoiceIssuer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.storagemanager.storage_management.config.InvoicingProperties.orMissing;

/**
 * Los campos que se pueden usar en una plantilla de contrato: qué hay, qué
 * significa cada uno y con qué se rellena.
 * <p>
 * Están aquí y no repartidos porque tienen que ser exactamente los mismos en dos
 * sitios: al componer el contrato y en la pantalla que edita la plantilla. Una
 * lista que se mantiene a mano en dos lados es una lista que un día miente, y el
 * día que mienta alguien escribirá {@code {{unidad_metros}}} en una plantilla y
 * saldrá impreso tal cual en un contrato firmado.
 */
public final class ContractFields {

    private ContractFields() {}

    /** Un campo de la plantilla: cómo se escribe, qué es y un ejemplo. */
    public record Field(String name, String group, String description, String example) {}

    /**
     * El catálogo, en el orden en que tiene sentido leerlo. Lo enseña la pantalla
     * de plantillas y es la única lista que hay.
     */
    public static final List<Field> CATALOGUE = List.of(
            new Field("fecha_larga", "Generales", "Fecha del contrato, escrita", "1 de marzo de 2026"),
            new Field("lugar", "Generales", "Municipio donde se firma", "Oleiros (A Coruña)"),
            new Field("contrato_numero", "Generales", "Número del contrato", "CON-2026-03"),

            new Field("arrendador_nombre", "Arrendador", "Nombre fiscal del propietario", "Comunidad de bienes Pasaxe 29"),
            new Field("arrendador_nif", "Arrendador", "NIF del propietario", "E56424500"),
            new Field("arrendador_direccion", "Arrendador", "Domicilio fiscal del propietario", "Avenida del Pasaje 29"),
            new Field("arrendador_ciudad", "Arrendador", "Municipio del propietario", "Oleiros (A Coruña)"),
            new Field("arrendador_email", "Arrendador", "Correo de contacto", "pasaxe29@ejemplo.es"),
            new Field("arrendador_telefono", "Arrendador", "Teléfono de contacto", "600 000 000"),
            new Field("arrendador_iban", "Arrendador", "Cuenta donde se cobra", "ES00 0000 0000 0000 0000 0000"),

            new Field("arrendatario_nombre", "Arrendatario", "Nombre del inquilino", "Ana Gómez Pérez"),
            new Field("arrendatario_nif", "Arrendatario", "NIF del inquilino", "12345678Z"),
            new Field("arrendatario_direccion", "Arrendatario", "Domicilio del inquilino", "Rúa Nova 1, 3º B"),
            new Field("arrendatario_email", "Arrendatario", "Correo del inquilino", "ana@ejemplo.es"),
            new Field("arrendatario_telefono", "Arrendatario", "Teléfono del inquilino", "600 111 222"),
            new Field("coarrendatario", "Arrendatario",
                    "Párrafo del segundo titular; queda vacío si el contrato es de uno solo",
                    "Y de otra parte, Luis Gómez Pérez, con NIF..."),

            new Field("unidad_numero", "Unidad", "Número de la unidad", "3"),
            new Field("unidad_nombre", "Unidad", "Nombre de la unidad", "Trastero 3"),
            new Field("unidad_metros", "Unidad", "Metros cuadrados", "5"),
            new Field("unidad_direccion", "Unidad", "Dónde está", "Avenida del Pasaje 29, bajo delantero"),

            new Field("fecha_inicio", "Fechas", "Cuándo empieza", "01/03/2026"),
            new Field("fecha_fin", "Fechas", "Cuándo termina; un guion si es indefinido", "28/02/2027"),
            new Field("duracion", "Fechas", "Frase hecha con la duración pactada",
                    "se prorrogará por periodos mensuales mientras ninguna de las partes lo denuncie"),

            new Field("renta_base", "Dinero", "Renta mensual sin IVA", "45,45 €"),
            new Field("renta_iva", "Dinero", "Cuota de IVA de la renta", "9,55 €"),
            new Field("renta_total", "Dinero", "Renta mensual total", "55,00 €"),
            new Field("iva_texto", "Dinero", "Explicación del IVA aplicado", "21 %, tipo general vigente"),
            new Field("dia_cobro", "Dinero", "Día de cobro pactado", "1"),
            new Field("fianza", "Dinero", "Importe de la fianza", "110,00 €"),
            new Field("fianza_texto", "Dinero", "Frase hecha con la fianza; dice que no hay si no la hay",
                    "EL ARRENDATARIO entrega a EL ARRENDADOR la cantidad de 110,00 € en concepto de fianza.")
    );

    /** Los nombres, para comprobar de un vistazo si una plantilla usa algo que no existe. */
    public static boolean exists(String name) {
        return CATALOGUE.stream().anyMatch(f -> f.name().equals(name));
    }

    /** Con qué se rellena cada campo en un contrato de verdad. */
    public static Map<String, String> of(RentalAgreement rental, InvoiceIssuer.Issuer issuer) {
        StorageUnit unit = rental.getStorageUnit();
        Client client = rental.getClient();
        boolean vatApplicable = unit != null && unit.isVatApplicable();
        VatUtils.Breakdown rent = VatUtils.breakdown(rental.getMonthlyRent(), vatApplicable);
        BigDecimal deposit = rental.getSecurityDeposit();

        Map<String, String> values = new LinkedHashMap<>();
        values.put("fecha_larga", Pdfs.longDay(rental.getStartDate() == null ? LocalDate.now() : rental.getStartDate()));
        values.put("lugar", orMissing(issuer.city()));
        values.put("contrato_numero", orMissing(rental.getAgreementNumber()));

        values.put("arrendador_nombre", orMissing(issuer.name()));
        values.put("arrendador_nif", orMissing(issuer.taxId()));
        values.put("arrendador_direccion", orMissing(issuer.address()));
        values.put("arrendador_ciudad", orMissing(issuer.city()));
        values.put("arrendador_email", orMissing(issuer.email()));
        values.put("arrendador_telefono", orMissing(issuer.phone()));
        values.put("arrendador_iban", orMissing(issuer.iban()));

        values.put("arrendatario_nombre", client == null ? orMissing(null) : client.getFullName());
        values.put("arrendatario_nif", client == null ? orMissing(null) : orMissing(client.getDocumentId()));
        values.put("arrendatario_direccion", client == null ? orMissing(null) : orMissing(client.getAddress()));
        values.put("arrendatario_email", client == null ? orMissing(null) : orMissing(client.getEmail()));
        values.put("arrendatario_telefono", client == null ? orMissing(null) : orMissing(client.getPhone()));
        values.put("coarrendatario", coTenant(rental.getCoClient()));

        values.put("unidad_numero", unit == null ? orMissing(null) : unit.getUnitNumber());
        values.put("unidad_nombre", unit == null ? orMissing(null) : unit.getName());
        values.put("unidad_metros", unit == null || unit.getSizeSquareMeters() == null
                ? orMissing(null) : trimZeros(unit.getSizeSquareMeters()));
        values.put("unidad_direccion", unit == null ? orMissing(null) : orMissing(unit.getLocation()));

        values.put("fecha_inicio", Pdfs.day(rental.getStartDate()));
        values.put("fecha_fin", Pdfs.day(rental.getEndDate()));
        values.put("duracion", rental.getEndDate() == null
                ? "se prorrogará por periodos mensuales mientras ninguna de las partes lo denuncie"
                : "termina el " + Pdfs.day(rental.getEndDate()));

        values.put("renta_base", Pdfs.euros(rent.base()));
        values.put("renta_iva", vatApplicable ? Pdfs.euros(rent.vat()) : Pdfs.euros(BigDecimal.ZERO));
        values.put("renta_total", Pdfs.euros(rent.total()));
        values.put("iva_texto", vatApplicable
                ? "21 %, tipo general vigente"
                : "operación exenta, artículo 20.Uno.23º de la Ley 37/1992");
        values.put("dia_cobro", String.valueOf(rental.getBillingDayOfMonth()));

        values.put("fianza", deposit == null ? Pdfs.euros(BigDecimal.ZERO) : Pdfs.euros(deposit));
        values.put("fianza_texto", deposit == null || deposit.signum() == 0
                ? "No se establece fianza."
                : "EL ARRENDATARIO entrega a EL ARRENDADOR la cantidad de " + Pdfs.euros(deposit)
                  + " en concepto de fianza.");
        return values;
    }

    /**
     * Un contrato de mentira, para la vista previa de la pantalla de plantillas.
     * <p>
     * Se inventa en memoria en vez de coger un alquiler de verdad: probar una
     * plantilla no debería sacar los datos de un inquilino real en un PDF que
     * acaba en la carpeta de descargas de cualquiera.
     */
    public static RentalAgreement sampleRental() {
        Client tenant = Client.builder()
                .fullName("Ana Gómez Pérez").documentId("12345678Z")
                .address("Rúa Nova 1, 3º B, 15172 Oleiros (A Coruña)")
                .email("ana@ejemplo.es").phone("600 111 222")
                .build();
        StorageUnit unit = StorageUnit.builder()
                .unitNumber("3").name("Trastero 3").kind(UnitKind.STORAGE_UNIT)
                .sizeSquareMeters(5.0)
                .location("Avenida del Pasaje (A Pasaxe) 29, bajo delantero")
                .build();
        return RentalAgreement.builder()
                .agreementNumber("CON-EJEMPLO").storageUnit(unit).client(tenant)
                .startDate(LocalDate.now()).billingDayOfMonth(1)
                .monthlyRent(new BigDecimal("55.00")).securityDeposit(new BigDecimal("110.00"))
                .build();
    }

    /** El párrafo del segundo titular, o nada cuando el contrato es de uno solo. */
    private static String coTenant(Client coClient) {
        if (coClient == null) return "";
        return "Y de otra parte, **" + coClient.getFullName() + "**, con NIF "
               + orMissing(coClient.getDocumentId())
               + ", que interviene igualmente como ARRENDATARIO y responde solidariamente "
               + "de las obligaciones de este contrato.";
    }

    /** 5.0 m² queda raro en un contrato; 5 m², no. */
    private static String trimZeros(Double size) {
        if (size == size.longValue()) return String.valueOf(size.longValue());
        return String.valueOf(size);
    }
}
