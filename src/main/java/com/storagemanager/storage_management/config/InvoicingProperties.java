package com.storagemanager.storage_management.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Lo que hace falta para emitir facturas y contratos y no es de nadie en
 * concreto ({@code boxvault.invoicing.*}): la serie de facturación, la plantilla
 * del contrato y unos datos de emisor de respaldo.
 * <p>
 * Quién emite NO se configura aquí: es el propietario de la unidad, y sus datos
 * —nombre, NIF, domicilio, IBAN— salen de su ficha, que es donde ya están y
 * donde los mantiene quien lleva la casa (ver {@code InvoiceIssuer}). Los
 * {@code issuer-*} de aquí sólo rellenan lo que falte en esa ficha, para una
 * instalación que todavía no tenga propietarios cargados.
 * <p>
 * Lo que falte sale en el PDF como un hueco visible ({@code ...}) en vez de
 * desaparecer sin más: una factura sin NIF no vale, y es mejor que se vea al
 * mirarla que descubrirlo cuando la rechacen.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "boxvault.invoicing")
public class InvoicingProperties {

    /** Marca lo que falta por rellenar; se ve de lejos en el PDF. */
    public static final String MISSING = "..........";

    /** Nombre fiscal de quien emite: la comunidad de bienes. */
    private String issuerName = "Comunidad de bienes Pasaxe 29";

    /** NIF de la comunidad (los de las comunidades de bienes empiezan por E). */
    private String issuerTaxId = "";

    /** Domicilio fiscal, en una línea. */
    private String issuerAddress = "";

    /** Código postal y municipio, en una línea ("15172 Oleiros (A Coruña)"). */
    private String issuerCity = "";

    /** Correo de contacto que se imprime en la factura. */
    private String issuerEmail = "";

    /** Teléfono de contacto que se imprime en la factura. */
    private String issuerPhone = "";

    /** IBAN donde se cobra; sale en la factura y en el contrato. */
    private String issuerIban = "";

    /**
     * Serie de las facturas. El número queda {@code serie + año + / + ordinal},
     * por ejemplo {@code A2026/0007}: correlativo dentro del año, que es lo que
     * pide el reglamento de facturación.
     */
    private String invoiceSeries = "A";

    /**
     * Plantilla del contrato de alquiler, en el classpath. Es texto plano con
     * marcas {@code {{campo}}}; cambiarla no obliga a tocar el código.
     */
    private String contractTemplate = "plantillas/contrato-alquiler.txt";

    /** El valor, o el hueco visible cuando no está configurado. */
    public static String orMissing(String value) {
        return value == null || value.isBlank() ? MISSING : value.trim();
    }
}
