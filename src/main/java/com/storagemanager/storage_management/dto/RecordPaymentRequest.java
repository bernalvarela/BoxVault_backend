package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.model.enums.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class RecordPaymentRequest {
    @NotNull(message = "Amount paid is required")
    @DecimalMin(value = "0.01", message = "Amount paid must be greater than 0")
    private BigDecimal amountPaid;

    @NotNull(message = "Payment method is required")
    private PaymentMethod paymentMethod;

    private LocalDate paymentDate = LocalDate.now();

    private String transactionReference;

    private String notes;
}
