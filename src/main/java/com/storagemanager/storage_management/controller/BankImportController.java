package com.storagemanager.storage_management.controller;

import com.storagemanager.storage_management.dto.BankDTOs.ApplyResultDTO;
import com.storagemanager.storage_management.dto.BankDTOs.ImportDTO;
import com.storagemanager.storage_management.dto.BankDTOs.ImportRequest;
import com.storagemanager.storage_management.dto.BankDTOs.ImportSummaryDTO;
import com.storagemanager.storage_management.dto.BankDTOs.LineDTO;
import com.storagemanager.storage_management.dto.BankDTOs.LineUpdateRequest;
import com.storagemanager.storage_management.dto.BankDTOs.ProfileDTO;
import com.storagemanager.storage_management.dto.BankDTOs.ProfileRequest;
import com.storagemanager.storage_management.dto.BankDTOs.RuleDTO;
import com.storagemanager.storage_management.service.bank.BankImportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * La importación de extractos bancarios: los perfiles de cada cuenta, los
 * extractos subidos con su revisión, y las reglas aprendidas.
 * <p>
 * Va con el permiso de Mensualidades: lo que crea son, sobre todo, cobros. Los
 * gastos y apuntes de la comunidad que salen de un extracto los crea el mismo
 * proceso, con lo que revisó quien tiene ese permiso.
 */
@RestController
@RequestMapping("/api/bank")
@RequiredArgsConstructor
public class BankImportController {

    private final BankImportService service;

    // ------------------------------------------------------------- Perfiles

    @PreAuthorize("@access.can('PAGOS','LEER')")
    @GetMapping("/profiles")
    public ResponseEntity<List<ProfileDTO>> profiles() {
        return ResponseEntity.ok(service.listProfiles());
    }

    @PreAuthorize("@access.can('PAGOS','ESCRIBIR')")
    @PostMapping("/profiles")
    public ResponseEntity<ProfileDTO> createProfile(@Valid @RequestBody ProfileRequest request) {
        return new ResponseEntity<>(service.createProfile(request), HttpStatus.CREATED);
    }

    @PreAuthorize("@access.can('PAGOS','ESCRIBIR')")
    @PutMapping("/profiles/{id}")
    public ResponseEntity<ProfileDTO> updateProfile(@PathVariable Long id, @Valid @RequestBody ProfileRequest request) {
        return ResponseEntity.ok(service.updateProfile(id, request));
    }

    @PreAuthorize("@access.can('PAGOS','ADMINISTRAR')")
    @DeleteMapping("/profiles/{id}")
    public ResponseEntity<Void> deleteProfile(@PathVariable Long id) {
        service.deleteProfile(id);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------- Extractos

    @PreAuthorize("@access.can('PAGOS','LEER')")
    @GetMapping("/imports")
    public ResponseEntity<List<ImportSummaryDTO>> imports() {
        return ResponseEntity.ok(service.listImports());
    }

    @PreAuthorize("@access.can('PAGOS','LEER')")
    @GetMapping("/imports/{id}")
    public ResponseEntity<ImportDTO> getImport(@PathVariable Long id) {
        return ResponseEntity.ok(service.getImport(id));
    }

    /** Sube un extracto ya leído en el navegador. No crea nada: deja la propuesta para revisarla. */
    @PreAuthorize("@access.can('PAGOS','ESCRIBIR')")
    @PostMapping("/imports")
    public ResponseEntity<ImportDTO> importStatement(@Valid @RequestBody ImportRequest request) {
        return new ResponseEntity<>(service.importStatement(request), HttpStatus.CREATED);
    }

    @PreAuthorize("@access.can('PAGOS','ESCRIBIR')")
    @PutMapping("/imports/{id}/lines/{lineId}")
    public ResponseEntity<LineDTO> updateLine(@PathVariable Long id, @PathVariable Long lineId,
                                              @RequestBody LineUpdateRequest request) {
        return ResponseEntity.ok(service.updateLine(id, lineId, request));
    }

    /** Crea los cobros, gastos y apuntes de las filas pendientes y completas. */
    @PreAuthorize("@access.can('PAGOS','ESCRIBIR')")
    @PostMapping("/imports/{id}/apply")
    public ResponseEntity<ApplyResultDTO> apply(@PathVariable Long id) {
        return ResponseEntity.ok(service.applyImport(id));
    }

    @PreAuthorize("@access.can('PAGOS','ESCRIBIR')")
    @DeleteMapping("/imports/{id}")
    public ResponseEntity<Void> deleteImport(@PathVariable Long id) {
        service.deleteImport(id);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------- Reglas

    @PreAuthorize("@access.can('PAGOS','LEER')")
    @GetMapping("/rules")
    public ResponseEntity<List<RuleDTO>> rules() {
        return ResponseEntity.ok(service.listRules());
    }

    @PreAuthorize("@access.can('PAGOS','ESCRIBIR')")
    @DeleteMapping("/rules/{id}")
    public ResponseEntity<Void> deleteRule(@PathVariable Long id) {
        service.deleteRule(id);
        return ResponseEntity.noContent().build();
    }
}
