package com.storagemanager.storage_management.controller;

import com.storagemanager.storage_management.dto.OwnershipDTO;
import com.storagemanager.storage_management.dto.OwnershipRequest;
import com.storagemanager.storage_management.service.OwnershipService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Shares of owners in units (a unit without shares of its own inherits its parent's). */
@RestController
@RequestMapping("/api/ownerships")
@RequiredArgsConstructor
public class OwnershipController {

    private final OwnershipService ownershipService;

    @PreAuthorize("@access.can('PROPIETARIOS','LEER')")
    @GetMapping
    public ResponseEntity<List<OwnershipDTO>> getOwnerships(
            @RequestParam(required = false) Long ownerId,
            @RequestParam(required = false) Long unitId) {
        return ResponseEntity.ok(ownershipService.getOwnerships(ownerId, unitId));
    }

    @PreAuthorize("@access.can('PROPIETARIOS','LEER')")
    @GetMapping("/{id}")
    public ResponseEntity<OwnershipDTO> getOwnershipById(@PathVariable Long id) {
        return ResponseEntity.ok(ownershipService.getOwnershipDtoById(id));
    }

    @PreAuthorize("@access.can('PROPIETARIOS','ESCRIBIR')")
    @PostMapping
    public ResponseEntity<OwnershipDTO> createOwnership(@Valid @RequestBody OwnershipRequest request) {
        return new ResponseEntity<>(ownershipService.createOwnership(request), HttpStatus.CREATED);
    }

    @PreAuthorize("@access.can('PROPIETARIOS','ESCRIBIR')")
    @PutMapping("/{id}")
    public ResponseEntity<OwnershipDTO> updateOwnership(@PathVariable Long id, @Valid @RequestBody OwnershipRequest request) {
        return ResponseEntity.ok(ownershipService.updateOwnership(id, request));
    }

    @PreAuthorize("@access.can('PROPIETARIOS','ADMINISTRAR')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteOwnership(@PathVariable Long id) {
        ownershipService.deleteOwnership(id);
        return ResponseEntity.noContent().build();
    }
}
