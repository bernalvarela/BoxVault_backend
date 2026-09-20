package com.storagemanager.storage_management.service.pdf;

import com.storagemanager.storage_management.config.VatUtils;
import com.storagemanager.storage_management.model.Client;
import com.storagemanager.storage_management.model.Owner;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.RentalParty;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.model.enums.PartyRole;
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
            new Field("arrendadores", "Arrendador",
                    "Todos los propietarios de la unidad, con su NIF, en una frase",
                    "Bernal Varela Gómez (NIF 46906413Y) y Xiao Varela Gómez (NIF 46906414F)"),
            new Field("arrendador[1]", "Arrendador",
                    "El primer propietario, con su NIF. Cambiando el número se llega al segundo, "
                    + "al tercero... tantos como tenga la unidad; si no hay tantos, queda vacío",
                    "Xiao Varela Gómez, con N.I.F. 46906414F"),
            new Field("arrendador[1]_nombre", "Arrendador",
                    "Igual que los campos de arriba pero de uno concreto: _nif, _direccion, "
                    + "_ciudad, _email, _telefono, _iban",
                    "Xiao Varela Gómez"),

            new Field("arrendatario_nombre", "Arrendatario", "Nombre del inquilino", "Ana Gómez Pérez"),
            new Field("arrendatario_nif", "Arrendatario", "NIF del inquilino", "12345678Z"),
            new Field("arrendatario_direccion", "Arrendatario", "Domicilio del inquilino", "Rúa Nova 1, 3º B"),
            new Field("arrendatario_email", "Arrendatario", "Correo del inquilino", "ana@ejemplo.es"),
            new Field("arrendatario_telefono", "Arrendatario", "Teléfono del inquilino", "600 111 222"),
            new Field("arrendatarios", "Arrendatario",
                    "Todos los arrendatarios del contrato, con su NIF, en una frase",
                    "Ana Gómez Pérez (NIF 12345678Z) y Luis Gómez Pérez (NIF 87654321X)"),
            new Field("arrendatario[1]", "Arrendatario",
                    "El primer arrendatario, con su NIF. Cambiando el número se llega al segundo, "
                    + "al tercero... tantos como firmen; si no hay tantos, queda vacío",
                    "Ana Gómez Pérez, con N.I.F. 12345678Z"),
            new Field("arrendatario[1]_nombre", "Arrendatario",
                    "Igual que los campos de arriba pero de uno concreto: _nif, _direccion, "
                    + "_email, _telefono",
                    "Ana Gómez Pérez"),
            new Field("fiador", "Arrendatario",
                    "Los fiadores solidarios con su NIF, si el contrato lleva alguno; vacío si no",
                    "Anthony Alejandro García Albarrán, con N.I.F. Y-7838295-R"),
            new Field("fiador[1]", "Arrendatario",
                    "Un fiador concreto; también _nombre, _nif, _direccion, _email, _telefono",
                    "Anthony Alejandro García Albarrán, con N.I.F. Y-7838295-R"),

            new Field("unidad_numero", "Unidad", "Número de la unidad", "3"),
            new Field("unidad_nombre", "Unidad", "Nombre de la unidad", "Trastero 3"),
            new Field("unidad_metros", "Unidad", "Metros cuadrados", "5"),
            new Field("unidad_direccion", "Unidad", "Dónde está", "Avenida del Pasaje 29, bajo delantero"),
            new Field("unidad_referencia_catastral", "Unidad", "Referencia catastral", "9602605NJ4090S0008KY"),
            new Field("inventario", "Unidad",
                    "Muebles y enseres del piso, uno por línea, tal como estén en su ficha",
                    "Cocina: muebles, vitrocerámica y horno..."),

            new Field("fecha_inicio", "Fechas", "Cuándo empieza", "01/03/2026"),
            new Field("fecha_fin", "Fechas", "Cuándo termina; un guion si es indefinido", "28/02/2027"),
            new Field("duracion", "Fechas", "Frase hecha con la duración pactada",
                    "se prorrogará por periodos mensuales mientras ninguna de las partes lo denuncie"),

            new Field("renta_base", "Dinero", "Renta mensual sin IVA", "45,45 €"),
            new Field("renta_iva", "Dinero", "Cuota de IVA de la renta", "9,55 €"),
            new Field("renta_total", "Dinero", "Renta mensual total", "55,00 €"),
            new Field("renta_anual", "Dinero", "Renta de un año (la mensual por doce)", "660,00 €"),
            new Field("iva_texto", "Dinero", "Explicación del IVA aplicado", "21 %, tipo general vigente"),
            new Field("dia_cobro", "Dinero", "Día de cobro pactado", "1"),
            new Field("gastos_comunidad", "Dinero",
                    "Cuota de comunidad mensual que asume el inquilino, si se pactó", "20,00 €"),
            new Field("gastos_ibi", "Dinero",
                    "IBI anual que asume el inquilino, si se pactó", "120,00 €"),
            new Field("fianza", "Dinero", "Importe de la fianza", "110,00 €"),
            new Field("fianza_texto", "Dinero", "Frase hecha con la fianza; dice que no hay si no la hay",
                    "EL ARRENDATARIO entrega a EL ARRENDADOR la cantidad de 110,00 € en concepto de fianza.")
    );

    /** El ejemplo que el catálogo da para un campo; "" si no existe. */
    public static String example(String name) {
        return CATALOGUE.stream()
                .filter(f -> f.name().equals(name))
                .map(Field::example)
                .findFirst()
                .orElse("");
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
        values.put("arrendadores", orMissing(issuer.ownersPhrase()));
        // arrendador[1], arrendador[2]... Los mismos datos de arriba, pero de
        // cada propietario por su cuenta: un piso de tres no cabe en un juego de
        // campos en singular, y cuántos hay sólo se sabe al componer el contrato.
        List<Owner> landlords = issuer.owners();
        for (int i = 0; i < landlords.size(); i++) {
            Owner landlord = landlords.get(i);
            person(values, "arrendador", i + 1, landlord.getFullName(), landlord.getDocumentId(),
                    landlord.getAddress(), landlord.getCity(), landlord.getEmail(),
                    landlord.getPhone(), landlord.getBankAccount());
        }

        // El singular es el titular, que es el primero de la lista; con los
        // índices se llega a los demás.
        values.put("arrendatario_nombre", client == null ? orMissing(null) : client.getFullName());
        values.put("arrendatario_nif", client == null ? orMissing(null) : orMissing(client.getDocumentId()));
        values.put("arrendatario_direccion", client == null ? orMissing(null) : orMissing(client.getAddress()));
        values.put("arrendatario_email", client == null ? orMissing(null) : orMissing(client.getEmail()));
        values.put("arrendatario_telefono", client == null ? orMissing(null) : orMissing(client.getPhone()));

        List<Client> tenants = rental.tenants();
        values.put("arrendatarios", phrase(tenants));
        for (int i = 0; i < tenants.size(); i++) {
            Client tenant = tenants.get(i);
            person(values, "arrendatario", i + 1, tenant.getFullName(), tenant.getDocumentId(),
                    tenant.getAddress(), null, tenant.getEmail(), tenant.getPhone(), null);
        }

        List<Client> guarantors = rental.guarantors();
        values.put("fiador", named(guarantors));
        for (int i = 0; i < guarantors.size(); i++) {
            Client guarantor = guarantors.get(i);
            person(values, "fiador", i + 1, guarantor.getFullName(), guarantor.getDocumentId(),
                    guarantor.getAddress(), null, guarantor.getEmail(), guarantor.getPhone(), null);
        }

        values.put("unidad_numero", unit == null ? orMissing(null) : unit.getUnitNumber());
        values.put("unidad_nombre", unit == null ? orMissing(null) : unit.getName());
        values.put("unidad_metros", unit == null || unit.getSizeSquareMeters() == null
                ? orMissing(null) : trimZeros(unit.getSizeSquareMeters()));
        values.put("unidad_direccion", unit == null ? orMissing(null) : orMissing(unit.getLocation()));
        values.put("unidad_referencia_catastral",
                unit == null ? orMissing(null) : orMissing(unit.getCadastralReference()));
        values.put("inventario", unit == null ? orMissing(null) : orMissing(unit.getInventory()));

        values.put("fecha_inicio", Pdfs.day(rental.getStartDate()));
        values.put("fecha_fin", Pdfs.day(rental.getEndDate()));
        values.put("duracion", rental.getEndDate() == null
                ? "se prorrogará por periodos mensuales mientras ninguna de las partes lo denuncie"
                : "termina el " + Pdfs.day(rental.getEndDate()));

        values.put("renta_base", Pdfs.euros(rent.base()));
        values.put("renta_iva", vatApplicable ? Pdfs.euros(rent.vat()) : Pdfs.euros(BigDecimal.ZERO));
        values.put("renta_total", Pdfs.euros(rent.total()));
        values.put("renta_anual", Pdfs.euros(rent.total().multiply(new BigDecimal("12"))));
        values.put("iva_texto", vatApplicable
                ? "21 %, tipo general vigente"
                : "operación exenta, artículo 20.Uno.23º de la Ley 37/1992");
        values.put("dia_cobro", String.valueOf(rental.getBillingDayOfMonth()));

        // Un gasto que no se pactó deja el hueco a la vista: en un contrato, un
        // importe en blanco tiene que verse, no leerse como "cero euros".
        values.put("gastos_comunidad", rental.getCommunityFee() == null
                ? orMissing(null) : Pdfs.euros(rental.getCommunityFee()));
        values.put("gastos_ibi", rental.getPropertyTax() == null
                ? orMissing(null) : Pdfs.euros(rental.getPropertyTax()));

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
     * <p>
     * Lleva TODO puesto -segundo titular, fiador, fecha de fin, gastos,
     * inventario, fianza- aunque un alquiler corriente no tenga la mitad. En una
     * vista previa, un campo vacío no se lee como "aquí no hay nada": se lee
     * como que la plantilla está mal. Y los trozos condicionales
     * ({@code [[si:fiador]]}) sólo se pueden revisar si su condición se cumple,
     * así que aquí se cumplen todas.
     */
    public static RentalAgreement sampleRental() {
        Client tenant = Client.builder()
                .fullName("Ana Gómez Pérez").documentId("12345678Z")
                .address("Rúa Nova 1, 3º B, 15172 Oleiros (A Coruña)")
                .email("ana@ejemplo.es").phone("600 111 222")
                .build();
        Client coTenant = Client.builder()
                .fullName("Luis Gómez Pérez").documentId("87654321X")
                .address("Rúa Nova 1, 3º B, 15172 Oleiros (A Coruña)")
                .email("luis@ejemplo.es").phone("600 333 444")
                .build();
        Client guarantor = Client.builder()
                .fullName("Carmen Pérez Souto").documentId("11223344M")
                .address("Rúa do Franco 8, 15702 Santiago de Compostela")
                .email("carmen@ejemplo.es").phone("600 555 666")
                .build();
        StorageUnit unit = StorageUnit.builder()
                .unitNumber("3").name("Trastero 3").kind(UnitKind.STORAGE_UNIT)
                .sizeSquareMeters(5.0)
                .location("Avenida del Pasaje (A Pasaxe) 29, bajo delantero")
                .cadastralReference("9602605NJ4090S0008KY")
                .inventory("Cocina: muebles, vitrocerámica y horno, nevera y lavadora.\nBaño: lavabo con espejo, WC y ducha.\nSalón: sofá, mesa de centro y mesa de comedor con cuatro sillas.")
                .build();
        LocalDate start = LocalDate.now();
        RentalAgreement sample = RentalAgreement.builder()
                .agreementNumber("CON-EJEMPLO").storageUnit(unit)
                // Las tres columnas de siempre, que son el reflejo de la lista.
                .client(tenant).coClient(coTenant).guarantor(guarantor)
                .startDate(start).endDate(start.plusYears(1)).billingDayOfMonth(1)
                .monthlyRent(new BigDecimal("55.00")).securityDeposit(new BigDecimal("110.00"))
                .communityFee(new BigDecimal("20.00")).propertyTax(new BigDecimal("120.00"))
                .build();
        sample.setParties(new java.util.ArrayList<>(List.of(
                RentalParty.builder().rentalAgreement(sample).client(tenant)
                        .role(PartyRole.ARRENDATARIO).position(0).build(),
                RentalParty.builder().rentalAgreement(sample).client(coTenant)
                        .role(PartyRole.ARRENDATARIO).position(1).build(),
                RentalParty.builder().rentalAgreement(sample).client(guarantor)
                        .role(PartyRole.FIADOR).position(2).build())));
        return sample;
    }

    /**
     * El arrendador de mentira de la vista previa, construido con los ejemplos
     * del propio catálogo.
     * <p>
     * No sale de la base a propósito, por dos razones. Una: así la vista previa
     * enseña EXACTAMENTE los mismos valores que la lista de campos del editor,
     * que es lo que se está aprendiendo al mirarla. Y dos: antes se cogía "el
     * primer propietario que conste" con todas las participaciones de la casa,
     * de modo que un PDF de prueba salía con los nombres y los NIF de todos los
     * propietarios reales; y si a alguno le faltaba un dato, la vista previa
     * enseñaba una línea de puntos que parecía un fallo de la plantilla sin
     * serlo. Para ver el contrato con los datos de verdad está la vista previa
     * del propio alquiler, que es donde eso importa.
     */
    public static InvoiceIssuer.Issuer sampleIssuer() {
        // Dos, para que {{arrendador[2]}} tenga algo que enseñar: una vista
        // previa con un solo propietario no deja revisar la plantilla de un piso
        // que es de dos.
        Owner first = Owner.builder()
                .fullName("Bernal Varela Gómez").documentId("46906413Y")
                .address(example("arrendador_direccion")).city(example("arrendador_ciudad"))
                .email(example("arrendador_email")).phone(example("arrendador_telefono"))
                .bankAccount(example("arrendador_iban"))
                .build();
        Owner second = Owner.builder()
                .fullName("Xiao Varela Gómez").documentId("46906414F")
                .address(example("arrendador_direccion")).city(example("arrendador_ciudad"))
                .email(example("arrendador_email")).phone(example("arrendador_telefono"))
                .bankAccount(example("arrendador_iban"))
                .build();
        return new InvoiceIssuer.Issuer(
                example("arrendador_nombre"),
                example("arrendador_nif"),
                example("arrendador_direccion"),
                example("arrendador_ciudad"),
                example("arrendador_email"),
                example("arrendador_telefono"),
                example("arrendador_iban"),
                true,
                example("arrendadores"),
                List.of(first, second));
    }

    /**
     * Escribe el juego de campos de una persona con su número:
     * {@code arrendatario[2]}, {@code arrendatario[2]_nombre},
     * {@code arrendatario[2]_nif}...
     * <p>
     * Lo que esa persona no tenga (un arrendatario no tiene IBAN ni municipio)
     * no se escribe: así el campo queda vacío en vez de imprimir una línea de
     * puntos prometiendo un dato que no existe.
     */
    private static void person(Map<String, String> values, String group, int number,
                               String name, String taxId, String address, String city,
                               String email, String phone, String iban) {
        String prefix = group + "[" + number + "]";
        values.put(prefix, named(name, taxId));
        values.put(prefix + "_nombre", orMissing(name));
        values.put(prefix + "_nif", orMissing(taxId));
        values.put(prefix + "_direccion", orMissing(address));
        if (city != null) values.put(prefix + "_ciudad", orMissing(city));
        values.put(prefix + "_email", orMissing(email));
        values.put(prefix + "_telefono", orMissing(phone));
        if (iban != null) values.put(prefix + "_iban", orMissing(iban));
    }

    /** "Fulano, con N.I.F. 12345678Z"; vacío cuando no hay nadie. */
    private static String named(String name, String taxId) {
        if (name == null || name.isBlank()) return "";
        return taxId == null || taxId.isBlank() ? name : name + ", con N.I.F. " + taxId;
    }

    /** Los que sean, uno detrás de otro: "Fulano, con N.I.F. X y Mengano, con N.I.F. Y". */
    private static String named(List<Client> people) {
        List<String> names = people.stream()
                .map(person -> named(person.getFullName(), person.getDocumentId()))
                .filter(text -> !text.isBlank())
                .toList();
        return join(names);
    }

    /** "Ana (NIF 1) y Luis (NIF 2)", como se escribe a las partes en un contrato. */
    private static String phrase(List<Client> people) {
        List<String> names = people.stream()
                .map(person -> person.getDocumentId() == null || person.getDocumentId().isBlank()
                        ? person.getFullName()
                        : person.getFullName() + " (NIF " + person.getDocumentId() + ")")
                .filter(text -> text != null && !text.isBlank())
                .toList();
        return names.isEmpty() ? orMissing(null) : join(names);
    }

    /** Comas entre todos menos el último, que lleva "y". */
    private static String join(List<String> names) {
        if (names.isEmpty()) return "";
        if (names.size() == 1) return names.get(0);
        return String.join(", ", names.subList(0, names.size() - 1)) + " y " + names.get(names.size() - 1);
    }

    /** 5.0 m² queda raro en un contrato; 5 m², no. */
    private static String trimZeros(Double size) {
        if (size == size.longValue()) return String.valueOf(size.longValue());
        return String.valueOf(size);
    }
}
