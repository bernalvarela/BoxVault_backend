package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.model.Client;
import com.storagemanager.storage_management.model.Payment;
import com.storagemanager.storage_management.model.StorageUnit;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Un mes de un contrato: lo que ese contrato debe en ese periodo y el cobro que
 * lo salda, si ya se ha recibido.
 * <p>
 * En BoxVault no se emiten recibos por adelantado: un {@link Payment} es siempre
 * dinero recibido, y lo que queda por cobrar sale de los propios contratos. Por
 * eso el cargo no se guarda en base de datos, se calcula
 * ({@code BillingService}) cada vez que se pide.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MonthlyChargeDTO {

    /** Identificador estable del cargo ("<contrato>-<año>-<mes>"), no hay fila propia en base de datos. */
    private String id;

    private Long rentalAgreementId;
    private String agreementNumber;
    private StorageUnit storageUnit;
    private Client client;

    private Integer billingPeriodYear;
    private Integer billingPeriodMonth;
    /** Día de cobro pactado en el contrato, dentro de ese mes. */
    private LocalDate dueDate;

    /** Renta del periodo: la del cobro si existe, si no la del contrato. */
    private BigDecimal amountDue;
    private BigDecimal amountPaid;
    /** Lo que falta por cobrar del periodo (nunca negativo). */
    private BigDecimal outstanding;

    /**
     * COLLECTED (cobrado), PENDING (mes en curso sin cobrar), OVERDUE (mes ya
     * cerrado sin cobrar) o WAIVED (marcado a mano como no cobrable).
     */
    private String status;

    /** El cobro recibido, cuando lo hay; null mientras el mes esté sin cobrar. */
    private Payment payment;
}
