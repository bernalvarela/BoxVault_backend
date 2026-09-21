package com.storagemanager.storage_management.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Cliente con su estado de actividad: activo = tiene al menos un alquiler ACTIVE.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientDTO {

    private Long id;
    private String fullName;
    private String email;
    private String phone;
    private String documentId;
    private String address;
    private String emergencyContact;
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private boolean active;
    private long activeRentalsCount;

    /**
     * Cuántos contratos en vigor avala sin alquilar nada.
     * <p>
     * Sin esto, un fiador aparece como "sin contratos activos" y parece una
     * ficha olvidada que alguien acabaría borrando.
     */
    private long guaranteedRentalsCount;

    /** El edificio de la ficha. */
    private Long buildingId;
    private String buildingName;

    /**
     * El NIF en limpio: mayúsculas y sin puntos, guiones ni espacios.
     * <p>
     * Es la clave por la que se agrupan las fichas de una misma persona. En los
     * datos conviven "Y-8033348-Z", "23.020.088-D" y "46906413Y"; agrupar por el
     * texto tal cual dejaría fuera justo a quien se quiere juntar.
     */
    private String personKey;

    /**
     * Cuántas fichas comparten ese NIF, ésta incluida. Más de una no es un
     * error: es la misma persona en dos edificios, cada una con su teléfono y
     * sus documentos.
     * <p>
     * Sólo se calcula para quien ve todos los edificios. Para los demás vale 1
     * siempre, y a propósito: decirle a alguien "ese NIF existe en otro sitio"
     * ya sería contarle algo de una persona con la que no tiene relación.
     */
    private long sameDocumentCount;
}
