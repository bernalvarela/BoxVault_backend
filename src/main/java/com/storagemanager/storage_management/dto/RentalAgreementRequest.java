package com.storagemanager.storage_management.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class RentalAgreementRequest {
    @NotNull(message = "Storage unit ID is required")
    private Long storageUnitId;

    @NotNull(message = "Client ID is required")
    private Long clientId;

    /** Optional second tenant (co-titular) of the contract. */
    private Long coClientId;

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

    private String notes;
}
