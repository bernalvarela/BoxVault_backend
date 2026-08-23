package com.storagemanager.storage_management.controller;

import com.storagemanager.storage_management.dto.StorageUnitRequest;
import com.storagemanager.storage_management.model.Client;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.model.enums.UnitStatus;
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

    @GetMapping
    public ResponseEntity<List<StorageUnit>> getAllUnits(
            @RequestParam(required = false) UnitStatus status) {
        if (status != null) {
            return ResponseEntity.ok(storageUnitService.getUnitsByStatus(status));
        }
        return ResponseEntity.ok(storageUnitService.getAllUnits());
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
