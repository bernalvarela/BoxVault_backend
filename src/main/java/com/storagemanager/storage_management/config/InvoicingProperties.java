package com.storagemanager.storage_management.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Quién emite las facturas y los contratos ({@code boxvault.invoicing.*}).
 * <p>
 * Son los datos de la comunidad de bienes propietaria de los bajos: su nombre,
 * su NIF, su dirección y la cuenta donde se domicilian los recibos. Van en la
 * configuración y no en la base de datos porque no cambian nunca y porque hacen
 * falta antes de que haya nada que consultar; el NIF y el IBAN, además, llegan
 * por variable de entorno en el servidor, que no es cosa de dejarlos escritos en
 * el repositorio.
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
