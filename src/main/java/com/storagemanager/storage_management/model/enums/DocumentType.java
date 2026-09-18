package com.storagemanager.storage_management.model.enums;

import java.util.List;

/**
 * Qué es un documento archivado, para poder filtrarlo y saber qué falta en una
 * ficha sin abrir el fichero. Los mismos valores sirven para los documentos del
 * cliente y para los del alquiler; cada formulario ofrece los que le tocan (ver
 * documentHelper.js en el frontend).
 * <p>
 * De la ficha del cliente:
 * DNI: documento de identidad (o pasaporte / NIE) del cliente.
 * CONTRATO_TRABAJO: contrato laboral, aportado como prueba de solvencia.
 * NOMINA: nómina o justificante de ingresos.
 * <p>
 * Del alquiler:
 * CONTRATO_ALQUILER: copia firmada del contrato de alquiler. Cuelga del
 *   alquiler, no del cliente: es de una unidad y unas fechas concretas. Las
 *   fichas de clientes anteriores a ese cambio pueden llevarlo todavía.
 * ANEXO: anexo o adenda al contrato (subida de precio, cambio de unidad...).
 * FACTURA: la factura de una mensualidad, con su número de serie y el desglose
 *   del IVA. La genera la propia aplicación y se archiva en el alquiler, que es
 *   donde están la unidad y el periodo a los que corresponde.
 * <p>
 * De cualquiera de los dos:
 * FOTO: fotografía del cliente o de la unidad en el momento de la entrega.
 * JUSTIFICANTE: recibo, transferencia o cualquier otro justificante; en una
 *   declaración presentada, el acuse con el CSV que devuelve la Sede electrónica.
 * <p>
 * De una declaración presentada:
 * DECLARACION: la declaración en sí, tal como se presentó (el PDF del modelo).
 * <p>
 * OTRO: lo que no encaja en las anteriores.
 */
public enum DocumentType {
    DNI,
    CONTRATO_TRABAJO,
    NOMINA,
    FOTO,
    CONTRATO_ALQUILER,
    ANEXO,
    FACTURA,
    JUSTIFICANTE,
    DECLARACION,
    OTRO;

    private static final List<DocumentType> FOR_CLIENT =
            List.of(DNI, CONTRATO_TRABAJO, NOMINA, FOTO, JUSTIFICANTE, OTRO);

    private static final List<DocumentType> FOR_RENTAL =
            List.of(CONTRATO_ALQUILER, ANEXO, FACTURA, FOTO, JUSTIFICANTE, OTRO);

    private static final List<DocumentType> FOR_TAX_FILING = List.of(JUSTIFICANTE, DECLARACION, OTRO);

    /**
     * Lo que se puede archivar en la ficha de un cliente. CONTRATO_ALQUILER no
     * está: el contrato es del alquiler. Las fichas anteriores a ese cambio
     * pueden tener alguno, y se siguen leyendo; lo que no se admite es subir uno
     * nuevo ahí.
     */
    public static List<DocumentType> forClient() {
        return FOR_CLIENT;
    }

    /** Lo que se puede archivar en un alquiler. */
    public static List<DocumentType> forRental() {
        return FOR_RENTAL;
    }

    /**
     * Lo que se puede archivar en una declaración presentada: el justificante que
     * devuelve la Sede electrónica al presentar, la declaración en sí y lo que no
     * sea ninguna de las dos (el fichero que se importó, un cálculo de apoyo).
     */
    public static List<DocumentType> forTaxFiling() {
        return FOR_TAX_FILING;
    }
}
