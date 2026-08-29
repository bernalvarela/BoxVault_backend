package com.storagemanager.storage_management.config;

import com.storagemanager.storage_management.dto.IrpfReportDTO;
import com.storagemanager.storage_management.dto.Modelo184DTO;
import com.storagemanager.storage_management.dto.Modelo303DTO;
import com.storagemanager.storage_management.model.StorageUnit;
import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Hints the GraalVM native image needs beyond what Spring Boot infers on its own:
 * <ul>
 *   <li>{@code seed-data.json}, read through {@code ClassPathResource} by the
 *       {@link DataSeeder}, must be bundled as a resource;</li>
 *   <li>the tax report DTOs are serialised to JSON by the seeder (snapshots of the
 *       filed returns) outside any controller, so their reflection metadata is
 *       registered explicitly (the registrar walks nested types and generics).</li>
 * </ul>
 * Registered from {@code StorageManagementApplication} via {@code @ImportRuntimeHints}.
 */
public class NativeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.resources().registerPattern("seed-data.json");

        BindingReflectionHintsRegistrar registrar = new BindingReflectionHintsRegistrar();
        registrar.registerReflectionHints(hints.reflection(),
                Modelo303DTO.class,
                Modelo184DTO.class,
                IrpfReportDTO.class,
                StorageUnit.UnitRef.class);
    }
}
