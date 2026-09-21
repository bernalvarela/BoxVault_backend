package com.storagemanager.storage_management.controller;

import com.storagemanager.storage_management.dto.CommunityDTOs.*;
import com.storagemanager.storage_management.service.CommunityService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * La comunidad de propietarios: su presupuesto, su libro y su estado de cuentas.
 * <p>
 * Va bajo el permiso de GASTOS porque es lo que es —llevar unas cuentas—, y
 * quien las lleva no tiene por qué poder tocar unidades ni contratos.
 */
@RestController
@RequestMapping("/api/communities")
@RequiredArgsConstructor
public class CommunityController {

    private final CommunityService communities;

    @PreAuthorize("@access.can('GASTOS','LEER')")
    @GetMapping
    public ResponseEntity<List<CommunityDTO>> list() {
        return ResponseEntity.ok(communities.list());
    }

    @PreAuthorize("@access.can('GASTOS','ADMINISTRAR')")
    @PostMapping
    public ResponseEntity<CommunityDTO> create(@Valid @RequestBody CommunityRequest request) {
        return new ResponseEntity<>(communities.create(request), HttpStatus.CREATED);
    }

    @PreAuthorize("@access.can('GASTOS','ADMINISTRAR')")
    @PutMapping("/{id}")
    public ResponseEntity<CommunityDTO> update(@PathVariable Long id, @Valid @RequestBody CommunityRequest request) {
        return ResponseEntity.ok(communities.update(id, request));
    }

    @PreAuthorize("@access.can('GASTOS','ADMINISTRAR')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        communities.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------- presupuesto

    @PreAuthorize("@access.can('GASTOS','LEER')")
    @GetMapping("/{id}/budgets")
    public ResponseEntity<List<BudgetDTO>> budgets(@PathVariable Long id) {
        return ResponseEntity.ok(communities.budgets(id));
    }

    /** Uno por ejercicio: volver a mandar el de un año lo corrige. */
    @PreAuthorize("@access.can('GASTOS','ESCRIBIR')")
    @PutMapping("/{id}/budgets")
    public ResponseEntity<BudgetDTO> saveBudget(@PathVariable Long id, @Valid @RequestBody BudgetRequest request) {
        return ResponseEntity.ok(communities.saveBudget(id, request));
    }

    @PreAuthorize("@access.can('GASTOS','ADMINISTRAR')")
    @DeleteMapping("/{id}/budgets/{budgetId}")
    public ResponseEntity<Void> deleteBudget(@PathVariable Long id, @PathVariable Long budgetId) {
        communities.deleteBudget(id, budgetId);
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------- el libro

    @PreAuthorize("@access.can('GASTOS','LEER')")
    @GetMapping("/{id}/entries")
    public ResponseEntity<List<EntryDTO>> book(@PathVariable Long id,
                                               @RequestParam(required = false) Integer year) {
        return ResponseEntity.ok(communities.book(id, year));
    }

    @PreAuthorize("@access.can('GASTOS','ESCRIBIR')")
    @PostMapping("/{id}/entries")
    public ResponseEntity<EntryDTO> addEntry(@PathVariable Long id, @Valid @RequestBody EntryRequest request) {
        return new ResponseEntity<>(communities.addEntry(id, request), HttpStatus.CREATED);
    }

    @PreAuthorize("@access.can('GASTOS','ESCRIBIR')")
    @PutMapping("/{id}/entries/{entryId}")
    public ResponseEntity<EntryDTO> updateEntry(@PathVariable Long id, @PathVariable Long entryId,
                                                @Valid @RequestBody EntryRequest request) {
        return ResponseEntity.ok(communities.updateEntry(id, entryId, request));
    }

    @PreAuthorize("@access.can('GASTOS','ESCRIBIR')")
    @DeleteMapping("/{id}/entries/{entryId}")
    public ResponseEntity<Void> deleteEntry(@PathVariable Long id, @PathVariable Long entryId) {
        communities.deleteEntry(id, entryId);
        return ResponseEntity.noContent().build();
    }

    // --------------------------------------------------- el estado de cuentas

    @PreAuthorize("@access.can('GASTOS','LEER')")
    @GetMapping("/{id}/statement")
    public ResponseEntity<StatementDTO> statement(@PathVariable Long id, @RequestParam int year) {
        return ResponseEntity.ok(communities.statement(id, year));
    }
}
