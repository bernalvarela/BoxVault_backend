package com.storagemanager.storage_management.controller;

import com.storagemanager.storage_management.dto.OwnerDTO;
import com.storagemanager.storage_management.dto.OwnerRequest;
import com.storagemanager.storage_management.service.OwnerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/owners")
@RequiredArgsConstructor
public class OwnerController {

    private final OwnerService ownerService;

    @GetMapping
    public ResponseEntity<List<OwnerDTO>> getAllOwners() {
        return ResponseEntity.ok(ownerService.getAllOwners());
    }

    @GetMapping("/{id}")
    public ResponseEntity<OwnerDTO> getOwnerById(@PathVariable Long id) {
        return ResponseEntity.ok(ownerService.getOwnerDtoById(id));
    }

    @PostMapping
    public ResponseEntity<OwnerDTO> createOwner(@Valid @RequestBody OwnerRequest request) {
        return new ResponseEntity<>(ownerService.createOwner(request), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    public ResponseEntity<OwnerDTO> updateOwner(@PathVariable Long id, @Valid @RequestBody OwnerRequest request) {
        return ResponseEntity.ok(ownerService.updateOwner(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteOwner(@PathVariable Long id) {
        ownerService.deleteOwner(id);
        return ResponseEntity.noContent().build();
    }
}
