package com.storagemanager.storage_management.controller;

import com.storagemanager.storage_management.service.signing.ContractSigningService;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Desde dónde llega una petición, para el registro de una firma.
 * <p>
 * Detrás de Traefik, la dirección de la conexión es la del proxy; la del
 * navegador viene en X-Forwarded-For. Esa cabecera la puede escribir
 * cualquiera que llegue sin pasar por el proxy, así que no se toma por buena a
 * ciegas: se guardan las dos, la de la conexión primero.
 */
final class RequestOrigin {

    private RequestOrigin() {}

    static ContractSigningService.Origin of(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = forwarded == null || forwarded.isBlank()
                ? remote
                : remote + " (vía " + forwarded.split(",")[0].trim() + ")";
        return new ContractSigningService.Origin(ip, request.getHeader("User-Agent"));
    }
}
