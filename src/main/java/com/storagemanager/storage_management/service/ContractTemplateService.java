package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.config.InvoicingProperties;
import com.storagemanager.storage_management.dto.ContractTemplateDTO;
import com.storagemanager.storage_management.dto.ContractTemplateRequest;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.ContractTemplate;
import com.storagemanager.storage_management.repository.ContractTemplateRepository;
import com.storagemanager.storage_management.repository.RentalAgreementRepository;
import com.storagemanager.storage_management.service.storage.FileStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Las plantillas de contrato: listarlas, leerlas, guardarlas y borrarlas.
 * <p>
 * El texto vive en el almacén de ficheros y la ficha en la base, igual que los
 * documentos adjuntos. Guardar sobrescribe el objeto: una plantilla no es un
 * documento entregado a nadie, es un molde, y no tiene sentido guardar cada
 * versión por la que ha pasado.
 * <p>
 * Siempre hay al menos una. Si la casa arranca sin ninguna, se siembra con la
 * que viene dentro de la aplicación ({@code plantillas/contrato-alquiler.txt}),
 * que es la que había antes de que esto se pudiera editar; así nadie se queda
 * sin poder generar un contrato por no haber creado todavía su plantilla.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractTemplateService {

    private static final String KEY_PREFIX = "plantillas/";

    private final ContractTemplateRepository templates;
    private final RentalAgreementRepository rentals;
    private final FileStorage fileStorage;
    private final InvoicingProperties properties;

    public List<ContractTemplateDTO> list() {
        return templates.findAllByOrderByNameAsc().stream().map(ContractTemplateDTO::of).toList();
    }

    /** La plantilla con su texto, que es lo que necesita la pantalla de edición. */
    public ContractTemplateDTO get(Long id) {
        ContractTemplate template = require(id);
        return ContractTemplateDTO.withContent(template, read(template));
    }

    @Transactional
    public ContractTemplateDTO create(ContractTemplateRequest request) {
        requireContent(request);
        if (templates.existsByNameIgnoreCase(request.getName().trim())) {
            throw new BadRequestException("Ya hay una plantilla que se llama " + request.getName().trim());
        }
        String key = KEY_PREFIX + UUID.randomUUID() + ".txt";
        byte[] content = request.getContent().getBytes(StandardCharsets.UTF_8);
        write(key, content);

        ContractTemplate template = ContractTemplate.builder()
                .name(request.getName().trim())
                .description(trimToNull(request.getDescription()))
                .storageKey(key)
                .sizeBytes((long) content.length)
                .defaultTemplate(false)
                .build();
        try {
            template = templates.save(template);
        } catch (RuntimeException e) {
            // Sin ficha, el objeto no es de nadie: no puede quedarse.
            safeDelete(key);
            throw e;
        }
        // La primera que se crea manda, aunque no lo pidan: si no, no habría
        // ninguna por defecto y los contratos no sabrían con cuál componerse.
        if (Boolean.TRUE.equals(request.getMakeDefault()) || templates.count() == 1) {
            makeDefault(template);
        }
        log.info("Creada la plantilla de contrato {} ({})", template.getName(), template.getId());
        return ContractTemplateDTO.of(template);
    }

    @Transactional
    public ContractTemplateDTO update(Long id, ContractTemplateRequest request) {
        requireContent(request);
        ContractTemplate template = require(id);

        byte[] content = request.getContent().getBytes(StandardCharsets.UTF_8);
        write(template.getStorageKey(), content);
        template.setName(request.getName().trim());
        template.setDescription(trimToNull(request.getDescription()));
        template.setSizeBytes((long) content.length);
        templates.save(template);

        if (Boolean.TRUE.equals(request.getMakeDefault())) makeDefault(template);
        return ContractTemplateDTO.of(template);
    }

    /**
     * Borra la plantilla. Los contratos que la tuvieran elegida pasan a usar la
     * de por defecto en vez de quedarse sin ninguna, y la de por defecto no se
     * puede borrar: sin ella, generar un contrato dejaría de funcionar.
     */
    @Transactional
    public void delete(Long id) {
        ContractTemplate template = require(id);
        if (template.isDefaultTemplate()) {
            throw new BadRequestException("No se puede borrar la plantilla por defecto. "
                    + "Marca otra como predeterminada y vuelve a intentarlo.");
        }
        long enUso = rentals.countByContractTemplateId(id);
        rentals.clearContractTemplate(id);
        templates.delete(template);
        safeDelete(template.getStorageKey());
        log.info("Borrada la plantilla {} ({}); {} contratos pasan a la de por defecto",
                template.getName(), id, enUso);
    }

    /** Marca ésta como la de por defecto y desmarca la que lo fuera. */
    @Transactional
    public ContractTemplateDTO makeDefault(Long id) {
        return ContractTemplateDTO.of(makeDefault(require(id)));
    }

    private ContractTemplate makeDefault(ContractTemplate template) {
        templates.findFirstByDefaultTemplateIsTrue().ifPresent(previous -> {
            if (!previous.getId().equals(template.getId())) {
                previous.setDefaultTemplate(false);
                templates.save(previous);
            }
        });
        template.setDefaultTemplate(true);
        return templates.save(template);
    }

    /**
     * El texto con el que se compone un contrato: el de su plantilla, el de la
     * que esté por defecto, o —si todavía no hay ninguna— el que viene dentro de
     * la aplicación.
     */
    public String textFor(ContractTemplate chosen) {
        if (chosen != null) return read(chosen);
        return templates.findFirstByDefaultTemplateIsTrue()
                .map(this::read)
                .orElseGet(this::bundledTemplate);
    }

    /**
     * Siembra la primera plantilla con la que viene dentro de la aplicación, si
     * no hay ninguna. La llama el arranque; no pisa nada.
     */
    @Transactional
    public void seedIfEmpty() {
        if (templates.count() > 0) return;
        ContractTemplateRequest request = new ContractTemplateRequest();
        request.setName("Contrato de trastero");
        request.setDescription("La plantilla que traía la aplicación. Cámbiala a tu gusto.");
        request.setContent(bundledTemplate());
        request.setMakeDefault(true);
        create(request);
        log.info("Sembrada la plantilla de contrato inicial");
    }

    // ------------------------------------------------------------------

    private ContractTemplate require(Long id) {
        return templates.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Contract template not found with id: " + id));
    }

    private void requireContent(ContractTemplateRequest request) {
        if (request.getContent() == null || request.getContent().isBlank()) {
            throw new BadRequestException("La plantilla no puede estar vacía");
        }
    }

    private String read(ContractTemplate template) {
        try (InputStream in = fileStorage.open(template.getStorageKey())) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer la plantilla " + template.getName(), e);
        }
    }

    private void write(String key, byte[] content) {
        fileStorage.put(key, "text/plain; charset=utf-8", content.length, new ByteArrayInputStream(content));
    }

    private void safeDelete(String key) {
        try {
            fileStorage.delete(key);
        } catch (RuntimeException e) {
            log.warn("No se pudo borrar del almacén la plantilla {}; queda huérfana", key, e);
        }
    }

    /** La plantilla que viaja dentro de la aplicación, la de siempre. */
    private String bundledTemplate() {
        ClassPathResource resource = new ClassPathResource(properties.getContractTemplate());
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "No se pudo leer la plantilla que trae la aplicación: " + properties.getContractTemplate(), e);
        }
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
