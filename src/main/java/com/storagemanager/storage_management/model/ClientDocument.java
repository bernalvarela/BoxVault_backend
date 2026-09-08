package com.storagemanager.storage_management.model;

import com.storagemanager.storage_management.model.enums.DocumentType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Un fichero archivado en la ficha de un cliente: copia del DNI, contrato de
 * trabajo, una foto...
 * <p>
 * La fila es sólo la ficha del documento; el contenido vive en el almacén de
 * objetos (RustFS por S3 en el servidor, el disco en desarrollo) bajo
 * {@link #storageKey}, que es la clave del objeto y nunca se enseña al
 * navegador: se descarga por el propio backend
 * ({@code /api/clients/{id}/documents/{docId}/download}), de modo que el almacén
 * no queda expuesto y no hace falta firmar URLs.
 */
@Entity
@Table(name = "client_documents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClientDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

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

    /** Clave del objeto en el almacén: {@code clients/{clientId}/{uuid}.{ext}}. */
    @Column(nullable = false, length = 400, unique = true)
    private String storageKey;

    @Column(length = 255)
    private String description;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime uploadedAt;
}
