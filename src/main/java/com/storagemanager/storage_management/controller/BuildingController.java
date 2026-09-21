package com.storagemanager.storage_management.controller;

import com.storagemanager.storage_management.dto.BuildingDTOs.BuildingDTO;
import com.storagemanager.storage_management.dto.BuildingDTOs.BuildingRequest;
import com.storagemanager.storage_management.service.BuildingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Los edificios. Leerlos lo puede hacer cualquiera que vea unidades —el selector
 * de la barra superior los necesita—; tocarlos, sólo quien administra.
 */
@RestController
@RequestMapping("/api/buildings")
@RequiredArgsConstructor
public class BuildingController {

    private final BuildingService buildings;

    @PreAuthorize("@access.can('UNIDADES','LEER')")
    @GetMapping
    public ResponseEntity<List<BuildingDTO>> list() {
        return ResponseEntity.ok(buildings.list());
    }

    @PreAuthorize("@access.can('UNIDADES','ADMINISTRAR')")
    @PostMapping
    public ResponseEntity<BuildingDTO> create(@Valid @RequestBody BuildingRequest request) {
        return new ResponseEntity<>(buildings.create(request), HttpStatus.CREATED);
    }

    @PreAuthorize("@access.can('UNIDADES','ADMINISTRAR')")
    @PutMapping("/{id}")
    public ResponseEntity<BuildingDTO> update(@PathVariable Long id, @Valid @RequestBody BuildingRequest request) {
        return ResponseEntity.ok(buildings.update(id, request));
    }

    @PreAuthorize("@access.can('UNIDADES','ADMINISTRAR')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        buildings.delete(id);
        return ResponseEntity.noContent().build();
    }
}
