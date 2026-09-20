package com.storagemanager.storage_management.controller;

import com.storagemanager.storage_management.dto.ContractTemplateDTO;
import com.storagemanager.storage_management.dto.ContractTemplateRequest;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.dto.TemplateImageDTO;
import com.storagemanager.storage_management.dto.TemplateRentalDTO;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.service.ContractTemplateService;
import com.storagemanager.storage_management.service.InvoiceIssuer;
import com.storagemanager.storage_management.service.RentalAgreementService;
import com.storagemanager.storage_management.service.TemplateImageService;
import com.storagemanager.storage_management.service.pdf.ContractFields;
import com.storagemanager.storage_management.service.pdf.ContractPdfService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Las plantillas con las que se componen los contratos.
 * <p>
 * Leerlas va con permiso de lectura de ALQUILERES —el formulario del contrato
 * necesita la lista para elegir—, pero tocarlas pide ADMINISTRAR: una plantilla
 * es el texto legal que van a firmar las dos partes, no un dato del día a día.
 */
@RestController
@RequestMapping("/api/contract-templates")
@RequiredArgsConstructor
public class ContractTemplateController {

    private final ContractTemplateService templates;
    private final ContractPdfService pdf;
    private final TemplateImageService images;
    private final RentalAgreementService rentals;
    private final InvoiceIssuer issuers;

    @PreAuthorize("@access.can('ALQUILERES','LEER')")
    @GetMapping
    public ResponseEntity<List<ContractTemplateDTO>> list() {
        return ResponseEntity.ok(templates.list());
    }

    /**
     * Los campos que se pueden escribir en una plantilla, con su descripción y un
     * ejemplo. Salen del mismo sitio que usa el generador, así que lo que enseña
     * la pantalla es exactamente lo que el contrato sabrá sustituir.
     */
    @PreAuthorize("@access.can('ALQUILERES','LEER')")
    @GetMapping("/fields")
    public ResponseEntity<List<ContractFields.Field>> fields() {
        return ResponseEntity.ok(ContractFields.CATALOGUE);
    }

    @PreAuthorize("@access.can('ALQUILERES','LEER')")
    @GetMapping("/{id}")
    public ResponseEntity<ContractTemplateDTO> get(@PathVariable Long id) {
        return ResponseEntity.ok(templates.get(id));
    }

    @PreAuthorize("@access.can('ALQUILERES','ADMINISTRAR')")
    @PostMapping
    public ResponseEntity<ContractTemplateDTO> create(@Valid @RequestBody ContractTemplateRequest request) {
        return new ResponseEntity<>(templates.create(request), HttpStatus.CREATED);
    }

    @PreAuthorize("@access.can('ALQUILERES','ADMINISTRAR')")
    @PutMapping("/{id}")
    public ResponseEntity<ContractTemplateDTO> update(@PathVariable Long id,
                                                     @Valid @RequestBody ContractTemplateRequest request) {
        return ResponseEntity.ok(templates.update(id, request));
    }

    @PreAuthorize("@access.can('ALQUILERES','ADMINISTRAR')")
    @PostMapping("/{id}/default")
    public ResponseEntity<ContractTemplateDTO> makeDefault(@PathVariable Long id) {
        return ResponseEntity.ok(templates.makeDefault(id));
    }

    @PreAuthorize("@access.can('ALQUILERES','ADMINISTRAR')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        templates.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Compone el PDF del texto que se está editando, sin guardarlo, con un
     * contrato de mentira: así se ve cómo queda —y si algún campo está mal
     * escrito— antes de dejarlo fijo para los contratos de verdad.
     * <p>
     * Con datos inventados a propósito: probar una plantilla no debería sacar los
     * datos de un inquilino real en un PDF que acaba en cualquier carpeta.
     */
    /** Las imágenes que una plantilla puede colocar: el logotipo, un sello. */
    @PreAuthorize("@access.can('ALQUILERES','LEER')")
    @GetMapping("/images")
    public ResponseEntity<List<TemplateImageDTO>> images() {
        return ResponseEntity.ok(images.list());
    }

    @PreAuthorize("@access.can('ALQUILERES','ADMINISTRAR')")
    @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TemplateImageDTO> uploadImage(@RequestPart("file") MultipartFile file,
                                                        @RequestParam(required = false) String name) {
        return new ResponseEntity<>(images.upload(name, file), HttpStatus.CREATED);
    }

    /** La imagen en sí, para enseñarla en la pantalla de plantillas. */
    @PreAuthorize("@access.can('ALQUILERES','LEER')")
    @GetMapping("/images/{id}/content")
    public ResponseEntity<Resource> imageContent(@PathVariable Long id) {
        TemplateImageService.Content content = images.open(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .contentLength(content.bytes().length)
                .body(new ByteArrayResource(content.bytes()));
    }

    @PreAuthorize("@access.can('ALQUILERES','ADMINISTRAR')")
    @DeleteMapping("/images/{id}")
    public ResponseEntity<Void> deleteImage(@PathVariable Long id) {
        images.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Copia la plantilla con otro nombre, para partir de ella sin tocarla. */
    @PreAuthorize("@access.can('ALQUILERES','ADMINISTRAR')")
    @PostMapping("/{id}/duplicate")
    public ResponseEntity<ContractTemplateDTO> duplicate(@PathVariable Long id) {
        return ResponseEntity.status(HttpStatus.CREATED).body(templates.duplicate(id));
    }

    /** Los alquileres que se componen con esta plantilla, para la vista previa. */
    @PreAuthorize("@access.can('ALQUILERES','ADMINISTRAR')")
    @GetMapping("/{id}/rentals")
    public ResponseEntity<List<TemplateRentalDTO>> rentalsUsing(@PathVariable Long id) {
        return ResponseEntity.ok(templates.rentalsUsing(id));
    }

    /**
     * Compone el texto para verlo.
     * <p>
     * Sin {@code rentalId} usa el contrato de mentira, que lleva todos los
     * campos puestos y sirve para revisar la redacción. Con él, los datos de ese
     * alquiler de verdad: es la única forma de ver si la cláusula de los gastos
     * dice lo que tiene que decir en el 3D, con sus tres propietarios y sus
     * importes.
     */
    @PreAuthorize("@access.can('ALQUILERES','ADMINISTRAR')")
    @PostMapping("/preview")
    public ResponseEntity<Resource> preview(@RequestBody ContractTemplateRequest request,
                                            @RequestParam(required = false) Long rentalId) {
        // Sin @Valid: para ver cómo queda un texto todavía no hace falta que
        // tenga nombre. Lo único que se exige es que haya texto.
        if (request.getContent() == null || request.getContent().isBlank()) {
            throw new BadRequestException("No hay nada que previsualizar");
        }
        RentalAgreement sample;
        InvoiceIssuer.Issuer issuer;
        if (rentalId == null) {
            sample = ContractFields.sampleRental();
            issuer = ContractFields.sampleIssuer();
        } else {
            // getAgreementById comprueba que la unidad esté en el ámbito de quien
            // mira: una vista previa no es un atajo para leer datos ajenos.
            sample = rentals.getAgreementById(rentalId);
            issuer = issuers.forUnit(sample.getStorageUnit());
        }
        byte[] content = pdf.render(sample, issuer, request.getContent(), images::bytesOf);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename("vista-previa.pdf", StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(content.length)
                .body(new ByteArrayResource(content));
    }
}
