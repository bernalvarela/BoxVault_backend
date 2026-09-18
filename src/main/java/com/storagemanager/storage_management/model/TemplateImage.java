package com.storagemanager.storage_management.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Una imagen que las plantillas de contrato pueden colocar: el logotipo, un
 * membrete, un sello.
 * <p>
 * Se referencia por su nombre desde la plantilla ({@code [[imagen:logo]]}) y no
 * por su id, porque quien escribe la plantilla escribe palabras, no números. Por
 * eso el nombre es único.
 * <p>
 * Los bytes viven en el almacén, como todo lo demás que pesa.
 */
@Entity
@Table(name = "template_images")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TemplateImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Con el que se la llama desde la plantilla: "logo", "sello". */
    @Column(nullable = false, unique = true, length = 60)
    private String name;

    @Column(nullable = false, length = 400, unique = true)
    private String storageKey;

    @Column(length = 120)
    private String contentType;

    private Long sizeBytes;

    /** El nombre del fichero que se subió, para reconocerla en la pantalla. */
    @Column(length = 255)
    private String fileName;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime uploadedAt;
}
