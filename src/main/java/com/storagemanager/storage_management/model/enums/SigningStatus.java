package com.storagemanager.storage_management.model.enums;

/**
 * En qué punto está la firma de un contrato.
 * EN_CURSO: preparada, con el PDF congelado; faltan firmas o falta sellarlo.
 * COMPLETADA: firmada por todos y sellada; el PDF firmado está archivado.
 * CANCELADA: se dejó a medias (había que cambiar algo del contrato); sus firmas
 *   no valen para nada, pero se guardan, con su registro.
 */
public enum SigningStatus {
    EN_CURSO,
    COMPLETADA,
    CANCELADA
}
