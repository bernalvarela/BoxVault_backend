package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.model.enums.ExpenseCategory;
import com.storagemanager.storage_management.model.enums.TaxModel;
import com.storagemanager.storage_management.model.enums.TaxNoticeKind;
import com.storagemanager.storage_management.model.enums.TaxNoticeStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Una notificación de la AEAT, con lo justo de su declaración y su gasto para enseñarlos al lado. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaxNoticeDTO {

    private Long id;
    private TaxNoticeKind kind;
    private TaxNoticeStatus status;
    private TaxModel model;
    private Integer year;
    private Integer quarter;
    private Long ownerId;
    private String ownerName;
    private String reference;
    private String description;
    private LocalDate receivedOn;
    private LocalDate responseDeadline;
    private LocalDate paymentDeadline;
    private BigDecimal amount;
    private BigDecimal reducedAmount;
    private LocalDate paidOn;
    private BigDecimal paidAmount;
    private String notes;
    private LocalDateTime createdAt;

    /** La declaración presentada de la que trae causa; null si no está registrada. */
    private Long filingId;
    /** Cuándo se presentó esa declaración, para verlo sin abrirla. */
    private LocalDate filingFiledDate;

    /** El gasto que recoge el pago; null si no se ha apuntado o se borró. */
    private Long expenseId;
    private LocalDate expenseDate;
    private BigDecimal expenseAmount;
    private String expenseDescription;
    private ExpenseCategory expenseCategory;

    private long documentCount;
}
