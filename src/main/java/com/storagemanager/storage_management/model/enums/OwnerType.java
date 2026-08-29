package com.storagemanager.storage_management.model.enums;

/**
 * Kind of owner. A person declares their rents in the IRPF; a comunidad de
 * bienes (an entity in "régimen de atribución de rentas") files the Modelo 303
 * and the Modelo 184 and attributes its income to its members (persons) by their
 * membership percentages.
 */
public enum OwnerType {
    PERSON,
    COMUNIDAD_DE_BIENES
}
