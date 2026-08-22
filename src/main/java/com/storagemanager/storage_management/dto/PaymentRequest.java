package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.model.enums.PaymentMethod;
import com.storagemanager.storage_management.model.enums.PaymentStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class PaymentRequest {
    @NotNull(message = "Rental agreement ID is required")
    private Long rentalAgreementId;

    @NotNull(message = "Billing month is required")
    private Integer billingPeriodMonth;

    @NotNull(message = "Billing year is required")
    private Integer billingPeriodYear;

    @NotNull(message = "Amount due is required")
    @DecimalMin(value = "0.0", message = "Amount due must be positive")
    private BigDecimal amountDue;

    private BigDecimal amountPaid = BigDecimal.ZERO;

    @NotNull(message = "Due date is required")
    private LocalDate dueDate;

    private LocalDate paymentDate;

    private PaymentStatus status = PaymentStatus.PENDING;

    private PaymentMethod paymentMethod;

    private String transactionReference;

    private String notes;
}
