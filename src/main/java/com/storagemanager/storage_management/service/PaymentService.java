package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.dto.PaymentRequest;
import com.storagemanager.storage_management.dto.RecordPaymentRequest;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.Payment;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.enums.PaymentMethod;
import com.storagemanager.storage_management.model.enums.PaymentStatus;
import com.storagemanager.storage_management.repository.PaymentRepository;
import com.storagemanager.storage_management.repository.RentalAgreementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Los cobros recibidos. Cada fila de {@link Payment} es dinero que ha entrado:
 * no se emiten recibos por adelantado, así que lo que queda por cobrar no vive
 * aquí sino que se deduce de los contratos en {@link BillingService}.
 */
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final RentalAgreementRepository rentalAgreementRepository;

    public List<Payment> getAllPayments() {
        return paymentRepository.findAll();
    }

    public Payment getPaymentById(Long id) {
        return paymentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found with id: " + id));
    }

    public List<Payment> getPaymentsByStatus(PaymentStatus status) {
        return paymentRepository.findByStatus(status);
    }

    public List<Payment> getPaymentsByMonthYear(Integer year, Integer month) {
        return paymentRepository.findByBillingPeriodYearAndBillingPeriodMonth(year, month);
    }

    public List<Payment> getPaymentsByRental(Long rentalAgreementId) {
        return paymentRepository.findByRentalAgreementId(rentalAgreementId);
    }

    public List<Payment> getPaymentsByClient(Long clientId) {
        return paymentRepository.findByClientId(clientId);
    }

    public List<Payment> getPaymentsByStorageUnit(Long unitId) {
        return paymentRepository.findByStorageUnitId(unitId);
    }

    @Transactional
    public Payment createPayment(PaymentRequest request) {
        RentalAgreement agreement = rentalAgreementRepository.findById(request.getRentalAgreementId())
                .orElseThrow(() -> new ResourceNotFoundException("Rental agreement not found with id: " + request.getRentalAgreementId()));

        Optional<Payment> existing = paymentRepository.findByRentalAgreementIdAndBillingPeriodYearAndBillingPeriodMonth(
                agreement.getId(), request.getBillingPeriodYear(), request.getBillingPeriodMonth());
        if (existing.isPresent()) {
            throw new BadRequestException("A payment record already exists for this agreement in " +
                    request.getBillingPeriodMonth() + "/" + request.getBillingPeriodYear());
        }

        Payment payment = Payment.builder()
                .rentalAgreement(agreement)
                .storageUnit(agreement.getStorageUnit())
                .client(agreement.getClient())
                .billingPeriodMonth(request.getBillingPeriodMonth())
                .billingPeriodYear(request.getBillingPeriodYear())
                .amountDue(request.getAmountDue())
                .amountPaid(request.getAmountPaid() != null ? request.getAmountPaid() : BigDecimal.ZERO)
                .dueDate(request.getDueDate())
                .paymentDate(request.getPaymentDate())
                .status(request.getStatus() != null ? request.getStatus() : PaymentStatus.PAID)
                .paymentMethod(request.getPaymentMethod())
                .transactionReference(request.getTransactionReference())
                .notes(request.getNotes())
                .build();

        return paymentRepository.save(payment);
    }

    @Transactional
    public Payment recordPayment(Long id, RecordPaymentRequest request) {
        Payment payment = getPaymentById(id);

        payment.setAmountPaid(request.getAmountPaid());
        payment.setPaymentDate(request.getPaymentDate() != null ? request.getPaymentDate() : LocalDate.now());
        payment.setPaymentMethod(request.getPaymentMethod());
        if (request.getTransactionReference() != null) {
            payment.setTransactionReference(request.getTransactionReference());
        }
        if (request.getNotes() != null) {
            payment.setNotes(request.getNotes());
        }

        // Una fila de pago es dinero recibido, cobre el mes entero o sólo una parte:
        // lo que falte lo refleja el cargo del periodo (BillingService), no el estado.
        payment.setStatus(PaymentStatus.PAID);

        return paymentRepository.save(payment);
    }

    @Transactional
    public Payment markAsPaid(Long id, PaymentMethod method, String reference) {
        Payment payment = getPaymentById(id);
        payment.setAmountPaid(payment.getAmountDue());
        payment.setPaymentDate(LocalDate.now());
        payment.setStatus(PaymentStatus.PAID);
        if (method != null) {
            payment.setPaymentMethod(method);
        }
        if (reference != null) {
            payment.setTransactionReference(reference);
        }
        return paymentRepository.save(payment);
    }

    @Transactional
    public void deletePayment(Long id) {
        Payment payment = getPaymentById(id);
        paymentRepository.delete(payment);
    }
}
