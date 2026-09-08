package com.storagemanager.storage_management.model.enums;

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
 * <p>
 * De cualquiera de los dos:
 * FOTO: fotografía del cliente o de la unidad en el momento de la entrega.
 * JUSTIFICANTE: recibo, transferencia o cualquier otro justificante.
 * OTRO: lo que no encaja en las anteriores.
 */
public enum DocumentType {
    DNI,
    CONTRATO_TRABAJO,
    NOMINA,
    FOTO,
    CONTRATO_ALQUILER,
    ANEXO,
    JUSTIFICANTE,
    OTRO
}
