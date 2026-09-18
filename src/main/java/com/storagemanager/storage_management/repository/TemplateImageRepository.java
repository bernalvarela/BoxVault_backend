package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.TemplateImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Las imágenes que pueden salir en un contrato. */
@Repository
public interface TemplateImageRepository extends JpaRepository<TemplateImage, Long> {

    List<TemplateImage> findAllByOrderByNameAsc();

    /** Por el nombre con el que la llama la plantilla ({@code [[imagen:logo]]}). */
    Optional<TemplateImage> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);
}
