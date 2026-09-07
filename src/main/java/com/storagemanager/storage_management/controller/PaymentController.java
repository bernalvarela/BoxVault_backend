package com.storagemanager.storage_management.controller;

import com.storagemanager.storage_management.dto.MonthlyChargeDTO;
import com.storagemanager.storage_management.dto.PaymentRequest;
import com.storagemanager.storage_management.dto.RecordPaymentRequest;
import com.storagemanager.storage_management.model.Payment;
import com.storagemanager.storage_management.model.enums.PaymentMethod;
import com.storagemanager.storage_management.model.enums.PaymentStatus;
import com.storagemanager.storage_management.service.BillingService;
import com.storagemanager.storage_management.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.util.List;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor

public class PaymentController {

    private final PaymentService paymentService;
    private final BillingService billingService;

    /**
     * Lo que cada contrato debe mes a mes, cobrado o no. Sin parámetros devuelve
     * todo el histórico; con año y mes, sólo ese periodo.
     */
    @GetMapping("/charges")
    public ResponseEntity<List<MonthlyChargeDTO>> getMonthlyCharges(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {

        if (year != null && month != null) {
            return ResponseEntity.ok(billingService.chargesOf(YearMonth.of(year, month)));
        }
        return ResponseEntity.ok(billingService.allCharges());
    }

    @GetMapping
    public ResponseEntity<List<Payment>> getAllPayments(
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Long storageUnitId,
            @RequestParam(required = false) Long clientId) {

        if (year != null && month != null) {
            return ResponseEntity.ok(paymentService.getPaymentsByMonthYear(year, month));
        }
        if (status != null) {
            return ResponseEntity.ok(paymentService.getPaymentsByStatus(status));
        }
        if (storageUnitId != null) {
            return ResponseEntity.ok(paymentService.getPaymentsByStorageUnit(storageUnitId));
        }
        if (clientId != null) {
            return ResponseEntity.ok(paymentService.getPaymentsByClient(clientId));
        }
        return ResponseEntity.ok(paymentService.getAllPayments());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Payment> getPaymentById(@PathVariable Long id) {
        return ResponseEntity.ok(paymentService.getPaymentById(id));
    }

    @PostMapping
    public ResponseEntity<Payment> createPayment(@Valid @RequestBody PaymentRequest request) {
        return new ResponseEntity<>(paymentService.createPayment(request), HttpStatus.CREATED);
    }

    @PostMapping("/{id}/record")
    public ResponseEntity<Payment> recordPayment(@PathVariable Long id, @Valid @RequestBody RecordPaymentRequest request) {
        return ResponseEntity.ok(paymentService.recordPayment(id, request));
    }

    @PostMapping("/{id}/mark-paid")
    public ResponseEntity<Payment> markAsPaid(
            @PathVariable Long id,
            @RequestParam(required = false, defaultValue = "BANK_TRANSFER") PaymentMethod method,
            @RequestParam(required = false) String reference) {
        return ResponseEntity.ok(paymentService.markAsPaid(id, method, reference));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePayment(@PathVariable Long id) {
        paymentService.deletePayment(id);
        return ResponseEntity.noContent().build();
    }
}
