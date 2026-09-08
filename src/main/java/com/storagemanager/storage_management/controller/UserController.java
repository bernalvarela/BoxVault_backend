package com.storagemanager.storage_management.controller;

import com.storagemanager.storage_management.dto.UserDTOs.RoleDTO;
import com.storagemanager.storage_management.dto.UserDTOs.UserDTO;
import com.storagemanager.storage_management.dto.UserDTOs.UserRequest;
import com.storagemanager.storage_management.model.enums.AccessArea;
import com.storagemanager.storage_management.model.enums.AccessLevel;
import com.storagemanager.storage_management.security.Authenticated;
import com.storagemanager.storage_management.service.UserAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Los usuarios de la aplicación y sus permisos. Todo esto pide el área USUARIOS:
 * leer para consultar la lista, administrar para tocarla —crear un usuario es
 * repartir acceso, así que no basta con escribir.
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserAdminService userService;

    /** Las áreas y los niveles, para pintar la rejilla de permisos. */
    @GetMapping("/access-areas")
    @PreAuthorize("@access.can('USUARIOS','LEER')")
    public ResponseEntity<Map<String, Object>> getAccessVocabulary() {
        return ResponseEntity.ok(Map.of(
                "areas", AccessArea.values(),
                "levels", AccessLevel.values()));
    }

    @GetMapping("/roles")
    @PreAuthorize("@access.can('USUARIOS','LEER')")
    public ResponseEntity<List<RoleDTO>> getRoles() {
        return ResponseEntity.ok(userService.getRoles());
    }

    @GetMapping
    @PreAuthorize("@access.can('USUARIOS','LEER')")
    public ResponseEntity<List<UserDTO>> getUsers() {
        return ResponseEntity.ok(userService.getUsers());
    }

    @GetMapping("/{id}")
    @PreAuthorize("@access.can('USUARIOS','LEER')")
    public ResponseEntity<UserDTO> getUser(@PathVariable Long id) {
        return ResponseEntity.ok(userService.getUser(id));
    }

    @PostMapping
    @PreAuthorize("@access.can('USUARIOS','ADMINISTRAR')")
    public ResponseEntity<UserDTO> create(@Valid @RequestBody UserRequest request) {
        return new ResponseEntity<>(userService.create(request), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@access.can('USUARIOS','ADMINISTRAR')")
    public ResponseEntity<UserDTO> update(@PathVariable Long id, @Valid @RequestBody UserRequest request) {
        return ResponseEntity.ok(userService.update(id, request, Authenticated.user()));
    }

    /**
     * Reinicia la contraseña de un usuario. No hay borrado de usuarios: se dan de
     * baja (active = false), para que sus rastros de auditoría sigan valiendo.
     */
    @PostMapping("/{id}/password")
    @PreAuthorize("@access.can('USUARIOS','ADMINISTRAR')")
    public ResponseEntity<Void> resetPassword(@PathVariable Long id, @RequestBody Map<String, String> body) {
        userService.resetPassword(id, body.get("password"));
        return ResponseEntity.noContent().build();
    }
}
