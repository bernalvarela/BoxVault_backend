package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.model.enums.DocumentType;

import java.time.Instant;

/** Lo que entra y sale al subir un documento de un cliente desde el móvil (ver MobileUploadService). */
public final class MobileUploadDTOs {

    private MobileUploadDTOs() {}

    /** Qué se va a subir: se decide en el ordenador, el móvil sólo hace la foto. */
    public record CreateRequest(DocumentType documentType, String description) {}

    /**
     * La sesión recién abierta. {@code token} es lo que va dentro del QR y no se
     * vuelve a enseñar; {@code id} es con lo que el ordenador pregunta cómo va.
     */
    public record Created(String id, String token, Instant expiresAt) {}

    /** Cómo va, para el ordenador que enseña el QR. */
    public record Status(MobileUploadStatus status, boolean opened, Instant expiresAt, Long documentId) {}

    /** Lo que enseña el móvil antes de hacer la foto: de quién y qué. */
    public record Info(String clientName, DocumentType documentType, String description, Instant expiresAt) {}

    public enum MobileUploadStatus { PENDING, DONE, EXPIRED, CANCELLED }
}
