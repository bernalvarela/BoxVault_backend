package com.storagemanager.storage_management.controller;

import com.storagemanager.storage_management.dto.OwnershipDTO;
import com.storagemanager.storage_management.dto.StorageUnitRequest;
import com.storagemanager.storage_management.dto.UnitHistoryDTO;
import com.storagemanager.storage_management.model.Client;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.model.enums.UnitKind;
import com.storagemanager.storage_management.model.enums.UnitStatus;
import com.storagemanager.storage_management.service.OwnershipService;
import com.storagemanager.storage_management.service.StorageUnitService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/storages")
@RequiredArgsConstructor

public class StorageUnitController {

    private final StorageUnitService storageUnitService;
    private final OwnershipService ownershipService;

    /** Lists units, optionally filtered by status and/or kind (STORAGE_UNIT / APARTMENT). */
    @PreAuthorize("@access.can('UNIDADES','LEER')")
    @GetMapping
    public ResponseEntity<List<StorageUnit>> getAllUnits(
            @RequestParam(required = false) UnitStatus status,
            @RequestParam(required = false) UnitKind kind) {
        return ResponseEntity.ok(storageUnitService.getUnits(kind, status));
    }

    @PreAuthorize("@access.can('UNIDADES','LEER')")
    @GetMapping("/{id}")
    public ResponseEntity<StorageUnit> getUnitById(@PathVariable Long id) {
        return ResponseEntity.ok(storageUnitService.getUnitById(id));
    }

    @PreAuthorize("@access.can('UNIDADES','LEER')")
    @GetMapping("/{id}/client")
    public ResponseEntity<Client> getClientByUnitId(@PathVariable Long id) {
        return storageUnitService.getClientByUnitId(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    @PreAuthorize("@access.can('UNIDADES','LEER')")
    @GetMapping("/{id}/history")
    public ResponseEntity<UnitHistoryDTO> getUnitHistory(@PathVariable Long id) {
        return ResponseEntity.ok(storageUnitService.getUnitHistory(id));
    }

    /** Effective owners of the unit: its own shares, or those of its group when it has none (flagged as inherited). */
    @PreAuthorize("@access.can('UNIDADES','LEER')")
    @GetMapping("/{id}/owners")
    public ResponseEntity<List<OwnershipDTO>> getUnitOwners(@PathVariable Long id) {
        return ResponseEntity.ok(ownershipService.getEffectiveOwnersOfUnit(id));
    }

    @PreAuthorize("@access.can('UNIDADES','ESCRIBIR')")
    @PostMapping
    public ResponseEntity<StorageUnit> createUnit(@Valid @RequestBody StorageUnitRequest request) {
        return new ResponseEntity<>(storageUnitService.createUnit(request), HttpStatus.CREATED);
    }

    @PreAuthorize("@access.can('UNIDADES','ESCRIBIR')")
    @PutMapping("/{id}")
    public ResponseEntity<StorageUnit> updateUnit(@PathVariable Long id, @Valid @RequestBody StorageUnitRequest request) {
        return ResponseEntity.ok(storageUnitService.updateUnit(id, request));
    }

    @PreAuthorize("@access.can('UNIDADES','ESCRIBIR')")
    @PatchMapping("/{id}/status")
    public ResponseEntity<Void> updateStatus(@PathVariable Long id, @RequestParam UnitStatus status) {
        storageUnitService.updateStatus(id, status);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("@access.can('UNIDADES','ADMINISTRAR')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUnit(@PathVariable Long id) {
        storageUnitService.deleteUnit(id);
        return ResponseEntity.noContent().build();
    }
}
