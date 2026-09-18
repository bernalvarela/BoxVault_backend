package com.storagemanager.storage_management.controller;

import com.storagemanager.storage_management.dto.ChargeAdjustmentRequest;
import com.storagemanager.storage_management.dto.MonthlyChargeDTO;
import com.storagemanager.storage_management.dto.PaymentRequest;
import com.storagemanager.storage_management.dto.RecordPaymentRequest;
import com.storagemanager.storage_management.model.Payment;
import com.storagemanager.storage_management.model.enums.PaymentMethod;
import com.storagemanager.storage_management.model.enums.PaymentStatus;
import com.storagemanager.storage_management.service.BillingService;
import com.storagemanager.storage_management.service.InvoiceService;
import com.storagemanager.storage_management.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
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
    private final InvoiceService invoiceService;

    /**
     * Lo que cada contrato debe mes a mes, cobrado o no. Sin parámetros devuelve
     * todo el histórico; con año y mes, sólo ese periodo.
     */
    @PreAuthorize("@access.can('PAGOS','LEER')")
    @GetMapping("/charges")
    public ResponseEntity<List<MonthlyChargeDTO>> getMonthlyCharges(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {

        if (year != null && month != null) {
            return ResponseEntity.ok(billingService.chargesOf(YearMonth.of(year, month)));
        }
        return ResponseEntity.ok(billingService.allCharges());
    }

    @PreAuthorize("@access.can('PAGOS','LEER')")
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

    @PreAuthorize("@access.can('PAGOS','LEER')")
    @GetMapping("/{id}")
    public ResponseEntity<Payment> getPaymentById(@PathVariable Long id) {
        return ResponseEntity.ok(paymentService.getPaymentById(id));
    }

    @PreAuthorize("@access.can('PAGOS','ESCRIBIR')")
    @PostMapping
    public ResponseEntity<Payment> createPayment(@Valid @RequestBody PaymentRequest request) {
        return new ResponseEntity<>(paymentService.createPayment(request), HttpStatus.CREATED);
    }

    @PreAuthorize("@access.can('PAGOS','ESCRIBIR')")
    @PostMapping("/{id}/record")
    public ResponseEntity<Payment> recordPayment(@PathVariable Long id, @Valid @RequestBody RecordPaymentRequest request) {
        return ResponseEntity.ok(paymentService.recordPayment(id, request));
    }

    @PreAuthorize("@access.can('PAGOS','ESCRIBIR')")
    @PostMapping("/{id}/mark-paid")
    public ResponseEntity<Payment> markAsPaid(
            @PathVariable Long id,
            @RequestParam(required = false, defaultValue = "BANK_TRANSFER") PaymentMethod method,
            @RequestParam(required = false) String reference) {
        return ResponseEntity.ok(paymentService.markAsPaid(id, method, reference));
    }

    /**
     * Corrige a mano la mensualidad de un contrato: el importe que se debe, lo
     * cobrado, o marcarla como no cobrable. Se direcciona por contrato y periodo,
     * no por id de cobro, porque un mes sin cobrar todavía no tiene fila propia.
     */
    @PreAuthorize("@access.can('PAGOS','ESCRIBIR')")
    @PutMapping("/charges/{rentalAgreementId}/{year}/{month}")
    public ResponseEntity<Payment> adjustCharge(
            @PathVariable Long rentalAgreementId,
            @PathVariable Integer year,
            @PathVariable Integer month,
            @Valid @RequestBody ChargeAdjustmentRequest request) {
        return ResponseEntity.ok(paymentService.adjustCharge(rentalAgreementId, year, month, request));
    }

    /** Deshace la corrección: el mes vuelve a ser el que dicen los contratos. */
    @PreAuthorize("@access.can('PAGOS','ADMINISTRAR')")
    @DeleteMapping("/charges/{rentalAgreementId}/{year}/{month}")
    public ResponseEntity<Void> clearChargeAdjustment(
            @PathVariable Long rentalAgreementId,
            @PathVariable Integer year,
            @PathVariable Integer month) {
        paymentService.clearChargeAdjustment(rentalAgreementId, year, month);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("@access.can('PAGOS','ADMINISTRAR')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePayment(@PathVariable Long id) {
        paymentService.deletePayment(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Emite la factura de la mensualidad y la devuelve en PDF. Si ya estaba
     * emitida devuelve esa misma: el número de una factura entregada no cambia.
     * Escribe (asigna el número y archiva el PDF), de ahí el POST.
     */
    @PreAuthorize("@access.can('PAGOS','ESCRIBIR')")
    @PostMapping("/{id}/invoice")
    public ResponseEntity<Resource> issueInvoice(@PathVariable Long id) {
        invoiceService.issue(id);
        return DocumentDownload.respond(invoiceService.open(id));
    }

    /** La factura ya emitida de una mensualidad. */
    @PreAuthorize("@access.can('PAGOS','LEER')")
    @GetMapping("/{id}/invoice")
    public ResponseEntity<Resource> getInvoice(@PathVariable Long id) {
        return DocumentDownload.respond(invoiceService.open(id));
    }
}
