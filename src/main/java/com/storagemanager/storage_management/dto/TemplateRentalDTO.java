package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.model.Client;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.enums.RentalStatus;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Un alquiler, lo justo para elegirlo en la vista previa de una plantilla.
 * <p>
 * No es el contrato entero: ahí sólo hace falta reconocerlo de un vistazo -qué
 * unidad, quién la alquila, si sigue en vigor- para decir "quiero verla con los
 * datos de éste".
 */
@Data
@Builder
public class TemplateRentalDTO {

    private Long id;
    private String agreementNumber;
    private String unitNumber;
    private String unitName;
    /** Los arrendatarios, para reconocer el contrato sin abrirlo. */
    private String tenants;
    private boolean active;

    public static TemplateRentalDTO of(RentalAgreement rental) {
        List<Client> tenants = rental.tenants();
        return TemplateRentalDTO.builder()
                .id(rental.getId())
                .agreementNumber(rental.getAgreementNumber())
                .unitNumber(rental.getStorageUnit() == null ? null : rental.getStorageUnit().getUnitNumber())
                .unitName(rental.getStorageUnit() == null ? null : rental.getStorageUnit().getName())
                .tenants(tenants.stream().map(Client::getFullName).reduce((a, b) -> a + " y " + b).orElse(""))
                .active(rental.getStatus() == RentalStatus.ACTIVE)
                .build();
    }
}
