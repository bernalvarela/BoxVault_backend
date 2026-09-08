package com.storagemanager.storage_management.model.enums;

/**
 * Qué es un documento archivado, para poder filtrarlo y saber qué falta en una
 * ficha sin abrir el fichero.
 * DNI: documento de identidad (o pasaporte / NIE) del cliente.
 * CONTRATO_TRABAJO: contrato laboral, aportado como prueba de solvencia.
 * NOMINA: nómina o justificante de ingresos.
 * FOTO: fotografía del cliente o de la unidad en el momento de la entrega.
 * CONTRATO_ALQUILER: copia firmada del contrato de alquiler.
 * JUSTIFICANTE: recibo, transferencia o cualquier otro justificante.
 * OTRO: lo que no encaja en las anteriores.
 */
public enum DocumentType {
    DNI,
    CONTRATO_TRABAJO,
    NOMINA,
    FOTO,
    CONTRATO_ALQUILER,
    JUSTIFICANTE,
    OTRO
}
