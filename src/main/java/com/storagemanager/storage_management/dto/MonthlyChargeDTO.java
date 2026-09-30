package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.model.Client;
import com.storagemanager.storage_management.model.Payment;
import com.storagemanager.storage_management.model.StorageUnit;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
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
    /** El titular, que es lo que cabe en una columna estrecha. */
    private Client client;

    /** Todos los que alquilan; en un contrato de dos, los dos pagan. */
    private List<PersonDTO> tenants;

    private Integer billingPeriodYear;
    private Integer billingPeriodMonth;
    /** Día de cobro pactado en el contrato, dentro de ese mes. */
    private LocalDate dueDate;

    /**
     * Lo que se debe del periodo: lo del cobro si existe; si no, lo del contrato
     * (la renta más los gastos que paga el inquilino).
     */
    private BigDecimal amountDue;

    /**
     * El desglose de {@code amountDue} cuando el contrato cobra gastos aparte:
     * renta, comunidad e IBI del mes. Nulos si no hay gastos, o si el importe del
     * mes se corrigió a mano y ya no es la suma del contrato (no se inventa un
     * reparto que nadie hizo).
     */
    private BigDecimal rentPart;
    private BigDecimal communityFeePart;
    private BigDecimal propertyTaxPart;
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
