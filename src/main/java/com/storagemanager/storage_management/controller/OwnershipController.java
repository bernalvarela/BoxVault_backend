package com.storagemanager.storage_management.controller;

import com.storagemanager.storage_management.dto.OwnershipDTO;
import com.storagemanager.storage_management.dto.OwnershipRequest;
import com.storagemanager.storage_management.service.OwnershipService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Shares of owners in storage groups and units. */
@RestController
@RequestMapping("/api/ownerships")
@RequiredArgsConstructor
public class OwnershipController {

    private final OwnershipService ownershipService;

    @GetMapping
    public ResponseEntity<List<OwnershipDTO>> getOwnerships(
            @RequestParam(required = false) Long ownerId,
            @RequestParam(required = false) Long unitId,
            @RequestParam(required = false) Long groupId) {
        return ResponseEntity.ok(ownershipService.getOwnerships(ownerId, unitId, groupId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OwnershipDTO> getOwnershipById(@PathVariable Long id) {
        return ResponseEntity.ok(ownershipService.getOwnershipDtoById(id));
    }

    @PostMapping
    public ResponseEntity<OwnershipDTO> createOwnership(@Valid @RequestBody OwnershipRequest request) {
        return new ResponseEntity<>(ownershipService.createOwnership(request), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    public ResponseEntity<OwnershipDTO> updateOwnership(@PathVariable Long id, @Valid @RequestBody OwnershipRequest request) {
        return ResponseEntity.ok(ownershipService.updateOwnership(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteOwnership(@PathVariable Long id) {
        ownershipService.deleteOwnership(id);
        return ResponseEntity.noContent().build();
    }
}
