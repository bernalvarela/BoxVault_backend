package com.storagemanager.storage_management.controller;

import com.storagemanager.storage_management.dto.IrpfReportDTO;
import com.storagemanager.storage_management.dto.Modelo184DTO;
import com.storagemanager.storage_management.dto.Modelo303DTO;
import com.storagemanager.storage_management.dto.Modelo303PresentationRequest;
import com.storagemanager.storage_management.dto.TaxFilingDTO;
import com.storagemanager.storage_management.dto.TaxFilingRequest;
import com.storagemanager.storage_management.model.enums.TaxModel;
import com.storagemanager.storage_management.service.Modelo303FileService;
import com.storagemanager.storage_management.service.Modelo303PresentationService;
import com.storagemanager.storage_management.service.TaxFilingService;
import com.storagemanager.storage_management.service.TaxService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Year;
import java.util.List;

/**
 * Tax helper reports and the register of filed returns. {@code year} defaults to
 * the current year; {@code ownerId} scopes the 303 / 184 to one owner (the
 * comunidad de bienes that files them).
 */
@RestController
@RequestMapping("/api/taxes")
@RequiredArgsConstructor
public class TaxController {

    private final TaxService taxService;
    private final TaxFilingService taxFilingService;
    private final Modelo303FileService modelo303FileService;
    private final Modelo303PresentationService modelo303PresentationService;

    private static int yearOrCurrent(Integer year) {
        return year != null ? year : Year.now().getValue();
    }

    /** Modelo 303: quarterly VAT of the VAT-bearing units, optionally only those held by an owner (the comunidad de bienes). */
    @PreAuthorize("@access.can('IMPUESTOS','LEER')")
    @GetMapping("/modelo-303")
    public ResponseEntity<Modelo303DTO> modelo303(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Long ownerId) {
        return ResponseEntity.ok(taxService.modelo303(yearOrCurrent(year), ownerId));
    }

    /**
     * Fichero de importación del Modelo 303 de un trimestre, con el diseño de registro
     * de la AEAT: se descarga y se sube en "Importar" del formulario web de la Sede.
     */
    @PreAuthorize("@access.can('IMPUESTOS','LEER')")
    @GetMapping("/modelo-303/fichero")
    public ResponseEntity<byte[]> modelo303File(
            @RequestParam(required = false) Integer year,
            @RequestParam int quarter,
            @RequestParam(required = false) Long ownerId,
            @RequestParam(required = false) Modelo303FileService.Basis basis,
            @RequestParam(required = false) BigDecimal pendingToOffset,
            @RequestParam(required = false) BigDecimal offsetApplied,
            @RequestParam(defaultValue = "false") boolean directDebit,
            @RequestParam(required = false) String rectifiesReceipt,
            @RequestParam(required = false) BigDecimal previouslyPaid,
            @RequestParam(defaultValue = "false") boolean administrativeCriterion) {
        Modelo303FileService.Options defaults = Modelo303FileService.Options.defaults();
        // Con el justificante de la anterior, el fichero sale como autoliquidación rectificativa.
        Modelo303FileService.Rectification rectification = rectifiesReceipt == null || rectifiesReceipt.isBlank()
                ? null
                : new Modelo303FileService.Rectification(rectifiesReceipt,
                        previouslyPaid != null ? previouslyPaid : BigDecimal.ZERO, administrativeCriterion);
        Modelo303FileService.Options options = new Modelo303FileService.Options(
                basis != null ? basis : defaults.basis(),
                pendingToOffset != null ? pendingToOffset : defaults.pendingToOffset(),
                offsetApplied != null ? offsetApplied : defaults.offsetApplied(),
                directDebit,
                rectification);
        Modelo303FileService.Modelo303File file =
                modelo303FileService.generate(yearOrCurrent(year), quarter, ownerId, options);
        // El fichero de la AEAT es texto de posiciones fijas en ISO-8859-1.
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .body(file.content().getBytes(StandardCharsets.ISO_8859_1));
    }

    /** Modelo 184: yearly income of a comunidad de bienes (default: the first one) attributed to its members. */
    @PreAuthorize("@access.can('IMPUESTOS','LEER')")
    @GetMapping("/modelo-184")
    public ResponseEntity<Modelo184DTO> modelo184(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Long ownerId) {
        return ResponseEntity.ok(taxService.modelo184(yearOrCurrent(year), ownerId));
    }

    /** IRPF: rental income (with deductible expenses) and atribución de rentas of the year, per person. */
    @PreAuthorize("@access.can('IMPUESTOS','LEER')")
    @GetMapping("/irpf")
    public ResponseEntity<IrpfReportDTO> irpf(@RequestParam(required = false) Integer year) {
        return ResponseEntity.ok(taxService.irpf(yearOrCurrent(year)));
    }

    // ------------------------------------------------------------------
    // Filed returns
    // ------------------------------------------------------------------

    @PreAuthorize("@access.can('IMPUESTOS','LEER')")
    @GetMapping("/filings")
    public ResponseEntity<List<TaxFilingDTO>> getFilings(
            @RequestParam(required = false) TaxModel model,
            @RequestParam(required = false) Integer year) {
        return ResponseEntity.ok(taxFilingService.getFilings(model, year));
    }

    @PreAuthorize("@access.can('IMPUESTOS','LEER')")
    @GetMapping("/filings/{id}")
    public ResponseEntity<TaxFilingDTO> getFiling(@PathVariable Long id) {
        return ResponseEntity.ok(taxFilingService.getFilingDtoById(id));
    }

    @PreAuthorize("@access.can('IMPUESTOS','ESCRIBIR')")
    @PostMapping("/filings")
    public ResponseEntity<TaxFilingDTO> createFiling(@Valid @RequestBody TaxFilingRequest request) {
        return new ResponseEntity<>(taxFilingService.createFiling(request), HttpStatus.CREATED);
    }

    /**
     * Presenta el Modelo 303 de un trimestre: genera el fichero para la Sede,
     * registra la declaración con las cifras de ahora y le archiva el fichero.
     * Devuelve las dos cosas; el fichero se baja por la url del documento.
     */
    @PreAuthorize("@access.can('IMPUESTOS','ESCRIBIR')")
    @PostMapping("/filings/modelo-303")
    public ResponseEntity<Modelo303PresentationService.Presentation> presentModelo303(
            @Valid @RequestBody Modelo303PresentationRequest request) {
        return new ResponseEntity<>(modelo303PresentationService.present(request), HttpStatus.CREATED);
    }

    @PreAuthorize("@access.can('IMPUESTOS','ADMINISTRAR')")
    @DeleteMapping("/filings/{id}")
    public ResponseEntity<Void> deleteFiling(@PathVariable Long id) {
        taxFilingService.deleteFiling(id);
        return ResponseEntity.noContent().build();
    }
}
