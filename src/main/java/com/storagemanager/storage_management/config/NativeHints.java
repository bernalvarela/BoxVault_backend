package com.storagemanager.storage_management.config;

import com.storagemanager.storage_management.dto.IrpfReportDTO;
import com.storagemanager.storage_management.dto.Modelo184DTO;
import com.storagemanager.storage_management.dto.Modelo303DTO;
import com.storagemanager.storage_management.model.StorageUnit;
import org.hibernate.dialect.PostgreSQLDialect;
import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.MemberCategory;
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

        // La plantilla del contrato, que se lee igual que el seed: por classpath.
        hints.resources().registerPattern("plantillas/*.txt");

        // Las fuentes base de OpenPDF. Las catorce fuentes estándar del formato
        // PDF no se empaquetan en el documento, pero sus métricas (.afm) sí hacen
        // falta para colocar el texto, y viajan como recursos dentro del jar.
        // En la imagen nativa, un recurso que nadie declara no se copia: sin esto
        // el primer PDF revienta con un "Helvetica not found" que no se entiende.
        hints.resources().registerPattern("org/openpdf/text/pdf/fonts/*");
        hints.resources().registerPattern("org/openpdf/text/error_messages/*");

        // El dialecto de PostgreSQL, por su constructor vacío.
        //
        // Normalmente Hibernate lo deduce preguntándole a la conexión y no hace
        // falta reflexión. Pero si la conexión falla —una contraseña que no es,
        // la base de datos todavía arrancando— tira del constructor por
        // reflexión, y en la imagen nativa eso reventaba con un
        // MissingReflectionRegistrationError que tapaba el error de verdad. El
        // AOT tampoco lo registra por su cuenta: se ejecuta sin perfil, o sea
        // con H2, y nunca ve este dialecto.
        hints.reflection().registerType(PostgreSQLDialect.class, MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);

        BindingReflectionHintsRegistrar registrar = new BindingReflectionHintsRegistrar();
        registrar.registerReflectionHints(hints.reflection(),
                Modelo303DTO.class,
                Modelo184DTO.class,
                IrpfReportDTO.class,
                StorageUnit.UnitRef.class);
    }
}
