package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.config.InvoicingProperties;
import com.storagemanager.storage_management.dto.ContractTemplateDTO;
import com.storagemanager.storage_management.dto.TemplateRentalDTO;
import com.storagemanager.storage_management.dto.ContractTemplateRequest;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.ContractTemplate;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.repository.ContractTemplateRepository;
import com.storagemanager.storage_management.repository.RentalAgreementRepository;
import com.storagemanager.storage_management.repository.StorageUnitRepository;
import com.storagemanager.storage_management.security.UnitScope;
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
import java.util.Comparator;
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

    private final UnitScope unitScope;
    private final ContractTemplateRepository templates;
    private final RentalAgreementRepository rentals;
    private final StorageUnitRepository units;
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

    /**
     * Copia una plantilla entera con otro nombre.
     * <p>
     * Es la forma sensata de empezar una variante: el contrato de un local se
     * parece al de un trastero en todo menos en tres cláusulas, y escribirlo de
     * cero para cambiar esas tres es tirar el trabajo. La copia nace sin ser la
     * de por defecto -eso se decide aparte- y con su propio objeto en el
     * almacén, así que editarla no toca a la original.
     */
    @Transactional
    public ContractTemplateDTO duplicate(Long id) {
        ContractTemplate original = require(id);
        ContractTemplateRequest copy = new ContractTemplateRequest();
        copy.setName(availableName(original.getName()));
        copy.setDescription(original.getDescription());
        copy.setContent(read(original));
        copy.setMakeDefault(false);
        log.info("Duplicada la plantilla {} como '{}'", original.getName(), copy.getName());
        return create(copy);
    }

    /**
     * "Contrato de trastero" -> "Contrato de trastero (copia)", y si ya existe,
     * "(copia 2)", "(copia 3)"... El nombre es único, así que hay que buscar uno
     * libre en vez de fallar y obligar a renombrar antes de copiar.
     */
    private String availableName(String name) {
        // El nombre no pasa de 120 caracteres: el original se recorta lo justo
        // para que quepa el sufijo, en vez de fallar al guardar.
        String base = name.length() > 100 ? name.substring(0, 100).trim() : name;
        String candidate = base + " (copia)";
        int number = 2;
        while (templates.existsByNameIgnoreCase(candidate)) {
            candidate = base + " (copia " + number + ")";
            number++;
        }
        return candidate;
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
        units.clearContractTemplate(id);
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
     * El texto con el que se compone un contrato, por orden: la plantilla que
     * diga el contrato, la que diga su unidad, la del local que la contiene, la
     * marcada por defecto y —si todavía no hay ninguna— la que viene dentro de la
     * aplicación.
     * <p>
     * La herencia por el árbol de unidades es la misma regla que la de los
     * propietarios: marcarla en el "Bajo delantero" vale para sus nueve
     * trasteros, sin repetirla nueve veces ni acordarse en cada alta.
     */
    public String textFor(RentalAgreement rental) {
        ContractTemplate chosen = templateFor(rental);
        return chosen == null ? bundledTemplate() : read(chosen);
    }

    /**
     * Con qué plantilla se compone el contrato de ese alquiler: la suya, la de
     * su unidad, la del local que la contiene o la de por defecto, por ese
     * orden. Nula cuando no hay ninguna creada y se usa la que trae la
     * aplicación dentro.
     */
    public ContractTemplate templateFor(RentalAgreement rental) {
        ContractTemplate chosen = rental.getContractTemplate() != null
                ? rental.getContractTemplate()
                : ofUnit(rental.getStorageUnit());
        return chosen != null ? chosen : templates.findFirstByDefaultTemplateIsTrue().orElse(null);
    }

    /**
     * Los alquileres que se componen con esta plantilla, para poder verla con
     * datos de verdad.
     * <p>
     * No basta con mirar quién la tiene elegida a mano: la mayoría de los
     * contratos no eligen ninguna y la heredan de su unidad, del local que la
     * contiene o del ajuste de por defecto. Así que se pregunta por cada
     * alquiler con qué se compondría -la misma cadena que al generarlo- y se
     * queda el que acabe en ésta. Los que el usuario no pueda ver por su ámbito
     * de unidades no salen.
     */
    public List<TemplateRentalDTO> rentalsUsing(Long templateId) {
        ContractTemplate template = require(templateId);
        List<RentalAgreement> theirs = rentals.findAll().stream()
                .filter(rental -> {
                    ContractTemplate used = templateFor(rental);
                    return used != null && used.getId().equals(template.getId());
                })
                .toList();

        return unitScope.filterByUnit(theirs, RentalAgreement::getStorageUnit).stream()
                // En vigor primero, y dentro de cada grupo por número de unidad:
                // el que se quiere mirar casi siempre es uno de los vivos.
                .sorted(Comparator.comparing(RentalAgreement::getStatus)
                        .thenComparing(rental -> rental.getStorageUnit() == null
                                ? "" : String.valueOf(rental.getStorageUnit().getUnitNumber())))
                .map(TemplateRentalDTO::of)
                .toList();
    }

    /** La plantilla de la unidad o la del local que la contiene; null si ninguna dice nada. */
    private ContractTemplate ofUnit(StorageUnit unit) {
        int guard = 0;
        for (StorageUnit u = unit; u != null && guard++ < 32; u = u.getParent()) {
            if (u.getContractTemplate() != null) return u.getContractTemplate();
        }
        return null;
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
