package com.storagemanager.storage_management.model;

import com.storagemanager.storage_management.model.enums.DocumentType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * La ficha de un fichero archivado: qué es, cómo se llamaba y dónde está
 * guardado. De quién es no se dice aquí: lo dicen las tablas de relación
 * ({@link ClientDocument}, {@link RentalDocument}), una por dueño.
 * <p>
 * Se hace así, y no con una columna por dueño, para que archivar documentos de
 * algo nuevo —un gasto, un propietario— sea añadir una relación y no tocar esta
 * tabla ni las filas que ya existen.
 * <p>
 * El contenido vive en el almacén de objetos (RustFS por S3 en el servidor, el
 * disco en desarrollo) bajo {@link #storageKey}, que nunca se enseña al
 * navegador: se descarga por el propio backend, de modo que el almacén no queda
 * expuesto y no hace falta firmar URLs.
 */
@Entity
@Table(name = "documents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private DocumentType documentType = DocumentType.OTRO;

    /** Nombre con el que se subió, el que se le devuelve al descargarlo. */
    @Column(nullable = false, length = 255)
    private String fileName;

    @Column(length = 120)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    /** Clave del objeto en el almacén: {@code clients/{id}/{uuid}.{ext}} o {@code rentals/...}. */
    @Column(nullable = false, length = 400, unique = true)
    private String storageKey;

    @Column(length = 255)
    private String description;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime uploadedAt;
}
