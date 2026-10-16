package com.storagemanager.storage_management.controller;

import com.storagemanager.storage_management.dto.MailSettingsDTOs;
import com.storagemanager.storage_management.service.MailSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * La configuración del correo saliente (Administración › Configuración). Sólo
 * para quien administra los usuarios: con ella se manda correo en nombre de la
 * comunidad.
 */
@RestController
@RequestMapping("/api/settings/mail")
@RequiredArgsConstructor
@PreAuthorize("@access.can('USUARIOS','ADMINISTRAR')")
public class MailSettingsController {

    private final MailSettingsService settings;

    @GetMapping
    public ResponseEntity<MailSettingsDTOs.View> get() {
        return ResponseEntity.ok(settings.get());
    }

    @PutMapping
    public ResponseEntity<MailSettingsDTOs.View> update(@RequestBody MailSettingsDTOs.Update request) {
        return ResponseEntity.ok(settings.update(request));
    }

    @PostMapping("/test")
    public ResponseEntity<MailSettingsDTOs.TestResult> test(@RequestBody MailSettingsDTOs.TestRequest request) {
        return ResponseEntity.ok(settings.test(request.to()));
    }
}
