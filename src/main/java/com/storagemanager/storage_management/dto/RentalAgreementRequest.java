package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.model.enums.PartyRole;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class RentalAgreementRequest {
    @NotNull(message = "Storage unit ID is required")
    private Long storageUnitId;

    @NotNull(message = "Client ID is required")
    private Long clientId;

    /** Optional second tenant (co-titular) of the contract. */
    private Long coClientId;

    /**
     * Quién firma el contrato y en calidad de qué, en orden: los arrendatarios
     * que haya y los fiadores que haya.
     * <p>
     * Si viene, manda: de aquí salen clientId, coClientId y guarantorId, que se
     * quedan como reflejo. Si no viene -una pantalla antigua, una llamada de
     * fuera- se usan esos tres como toda la vida. Así el cambio no rompe a
     * nadie mientras las dos formas convivan.
     */
    private List<Party> parties;

    /** Una persona del contrato: quién es y a qué viene. */
    @Data
    public static class Party {
        @NotNull(message = "Cada parte del contrato necesita un cliente")
        private Long clientId;
        /** Nulo = arrendatario, que es el caso normal. */
        private PartyRole role;
    }

    @NotNull(message = "Start date is required")
    private LocalDate startDate;

    private LocalDate endDate;

    private Integer billingDayOfMonth = 1;

    @NotNull(message = "Monthly rent is required")
    @DecimalMin(value = "0.0", message = "Monthly rent cannot be negative")
    private BigDecimal monthlyRent;

    private BigDecimal securityDeposit;

    private Boolean depositPaid = false;

    private Boolean autoRenew = true;

    /**
     * Si de este contrato se emiten facturas al cobrar. Sólo vale en unidades con
     * IVA: el alquiler de vivienda está exento y no se factura.
     */
    private Boolean generatesInvoices;

    /** Plantilla con la que se compone su contrato; nulo = la de por defecto. */
    private Long contractTemplateId;

    /** Fiador solidario, si lo hay; es una ficha de cliente que no alquila nada. */
    private Long guarantorId;

    /** Cuota de comunidad mensual que asume el inquilino; nulo = no se pacta. */
    private BigDecimal communityFee;

    /** IBI anual que asume el inquilino; nulo = no se pacta. */
    private BigDecimal propertyTax;

    private String notes;
}
