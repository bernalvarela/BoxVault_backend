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
    @GetMapping
    public ResponseEntity<List<StorageUnit>> getAllUnits(
            @RequestParam(required = false) UnitStatus status,
            @RequestParam(required = false) UnitKind kind) {
        return ResponseEntity.ok(storageUnitService.getUnits(kind, status));
    }

    @GetMapping("/{id}")
    public ResponseEntity<StorageUnit> getUnitById(@PathVariable Long id) {
        return ResponseEntity.ok(storageUnitService.getUnitById(id));
    }

    @GetMapping("/{id}/client")
    public ResponseEntity<Client> getClientByUnitId(@PathVariable Long id) {
        return storageUnitService.getClientByUnitId(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<UnitHistoryDTO> getUnitHistory(@PathVariable Long id) {
        return ResponseEntity.ok(storageUnitService.getUnitHistory(id));
    }

    /** Effective owners of the unit: its own shares, or those of its group when it has none (flagged as inherited). */
    @GetMapping("/{id}/owners")
    public ResponseEntity<List<OwnershipDTO>> getUnitOwners(@PathVariable Long id) {
        return ResponseEntity.ok(ownershipService.getEffectiveOwnersOfUnit(id));
    }

    @PostMapping
    public ResponseEntity<StorageUnit> createUnit(@Valid @RequestBody StorageUnitRequest request) {
        return new ResponseEntity<>(storageUnitService.createUnit(request), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    public ResponseEntity<StorageUnit> updateUnit(@PathVariable Long id, @Valid @RequestBody StorageUnitRequest request) {
        return ResponseEntity.ok(storageUnitService.updateUnit(id, request));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<Void> updateStatus(@PathVariable Long id, @RequestParam UnitStatus status) {
        storageUnitService.updateStatus(id, status);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUnit(@PathVariable Long id) {
        storageUnitService.deleteUnit(id);
        return ResponseEntity.noContent().build();
    }
}
