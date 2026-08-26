package com.storagemanager.storage_management.controller;

import com.storagemanager.storage_management.dto.StorageGroupDTO;
import com.storagemanager.storage_management.dto.StorageGroupRequest;
import com.storagemanager.storage_management.service.StorageGroupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/storage-groups")
@RequiredArgsConstructor
public class StorageGroupController {

    private final StorageGroupService storageGroupService;

    @GetMapping
    public ResponseEntity<List<StorageGroupDTO>> getAllGroups() {
        return ResponseEntity.ok(storageGroupService.getAllGroups());
    }

    @GetMapping("/{id}")
    public ResponseEntity<StorageGroupDTO> getGroupById(@PathVariable Long id) {
        return ResponseEntity.ok(storageGroupService.getGroupDtoById(id));
    }

    @PostMapping
    public ResponseEntity<StorageGroupDTO> createGroup(@Valid @RequestBody StorageGroupRequest request) {
        return new ResponseEntity<>(storageGroupService.createGroup(request), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    public ResponseEntity<StorageGroupDTO> updateGroup(@PathVariable Long id, @Valid @RequestBody StorageGroupRequest request) {
        return ResponseEntity.ok(storageGroupService.updateGroup(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteGroup(@PathVariable Long id) {
        storageGroupService.deleteGroup(id);
        return ResponseEntity.noContent().build();
    }
}
