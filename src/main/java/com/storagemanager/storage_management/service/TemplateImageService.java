package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.config.StorageProperties;
import com.storagemanager.storage_management.dto.TemplateImageDTO;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.TemplateImage;
import com.storagemanager.storage_management.repository.TemplateImageRepository;
import com.storagemanager.storage_management.service.pdf.Pdfs;
import com.storagemanager.storage_management.service.storage.FileStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Las imágenes que pueden salir en un contrato: el logotipo, un membrete, un
 * sello. Se suben aquí y las plantillas las colocan por su nombre.
 * <p>
 * Sólo formatos que el PDF sabe incrustar de verdad —JPEG, PNG y GIF—. Un HEIC
 * del móvil o un TIFF del escáner se admiten como adjunto de una ficha, pero
 * aquí no: acabarían en un contrato que no se puede abrir.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateImageService {

    private static final String KEY_PREFIX = "plantillas/imagenes/";
    private static final List<String> SUPPORTED = List.of("image/jpeg", "image/png", "image/gif");

    private final TemplateImageRepository images;
    private final FileStorage fileStorage;
    private final StorageProperties properties;

    public List<TemplateImageDTO> list() {
        return images.findAllByOrderByNameAsc().stream().map(TemplateImageDTO::of).toList();
    }

    @Transactional
    public TemplateImageDTO upload(String name, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("No se ha recibido ninguna imagen");
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!SUPPORTED.contains(contentType)) {
            throw new BadRequestException("Un contrato sólo puede llevar imágenes JPEG, PNG o GIF (llegó " + contentType + ")");
        }
        long max = properties.getMaxFileSize().toBytes();
        if (file.getSize() > max) {
            throw new BadRequestException("La imagen ocupa " + (file.getSize() / (1024 * 1024))
                    + " MB; el máximo son " + properties.getMaxFileSize().toMegabytes() + " MB");
        }

        // El nombre con el que se la llamará desde la plantilla: sin tildes ni
        // espacios, porque va dentro de [[imagen:...]].
        String key = Pdfs.slug(name != null && !name.isBlank() ? name : file.getOriginalFilename());
        if (key.isEmpty()) throw new BadRequestException("La imagen necesita un nombre");
        if (images.existsByNameIgnoreCase(key)) {
            throw new BadRequestException("Ya hay una imagen que se llama " + key);
        }

        String storageKey = KEY_PREFIX + UUID.randomUUID() + extensionOf(contentType);
        try (InputStream in = file.getInputStream()) {
            fileStorage.put(storageKey, contentType, file.getSize(), in);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer la imagen subida", e);
        }

        try {
            TemplateImage saved = images.save(TemplateImage.builder()
                    .name(key)
                    .storageKey(storageKey)
                    .contentType(contentType)
                    .sizeBytes(file.getSize())
                    .fileName(file.getOriginalFilename())
                    .build());
            log.info("Subida la imagen de plantilla {}", key);
            return TemplateImageDTO.of(saved);
        } catch (RuntimeException e) {
            safeDelete(storageKey);
            throw e;
        }
    }

    @Transactional
    public void delete(Long id) {
        TemplateImage image = images.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Template image not found with id: " + id));
        images.delete(image);
        safeDelete(image.getStorageKey());
    }

    /** Los bytes de una imagen por su nombre; null si no está. Es lo que usa el compositor. */
    public byte[] bytesOf(String name) {
        return images.findByNameIgnoreCase(name)
                .map(image -> {
                    try (InputStream in = fileStorage.open(image.getStorageKey())) {
                        return in.readAllBytes();
                    } catch (IOException | RuntimeException e) {
                        log.warn("No se pudo leer la imagen {} de la plantilla: {}", name, e.getMessage());
                        return null;
                    }
                })
                .orElse(null);
    }

    /** Los bytes y el tipo, para enseñarla en la pantalla de plantillas. */
    public record Content(byte[] bytes, String contentType, String fileName) {}

    public Content open(Long id) {
        TemplateImage image = images.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Template image not found with id: " + id));
        try (InputStream in = fileStorage.open(image.getStorageKey())) {
            return new Content(in.readAllBytes(), image.getContentType(), image.getFileName());
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer la imagen " + image.getName(), e);
        }
    }

    private void safeDelete(String key) {
        try {
            fileStorage.delete(key);
        } catch (RuntimeException e) {
            log.warn("No se pudo borrar del almacén la imagen {}; queda huérfana", key, e);
        }
    }

    private static String extensionOf(String contentType) {
        return switch (contentType) {
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            default -> ".jpg";
        };
    }
}
