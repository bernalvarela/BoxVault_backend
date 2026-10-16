package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.model.enums.MailSecurity;

import java.time.LocalDateTime;

/** La configuración del correo saliente, en la pantalla de configuración. */
public final class MailSettingsDTOs {

    private MailSettingsDTOs() {}

    /**
     * Lo que ve la pantalla. La contraseña no viaja nunca: sólo si hay una.
     */
    public record View(boolean enabled, String host, int port, MailSecurity security, String username,
                       boolean hasPassword, String fromAddress, String fromName, String copyTo,
                       LocalDateTime updatedAt, String updatedBy) {}

    /**
     * Lo que se guarda.
     *
     * @param password      la contraseña nueva; null o vacía, se queda la que había
     * @param clearPassword quitar la contraseña guardada
     */
    public record Update(boolean enabled, String host, Integer port, MailSecurity security, String username,
                         String password, boolean clearPassword, String fromAddress, String fromName,
                         String copyTo) {}

    /** Mandar un correo de prueba a esa dirección. */
    public record TestRequest(String to) {}

    public record TestResult(boolean sent, String message) {}
}
