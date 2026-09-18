package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.dto.ChargeAdjustmentRequest;
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
import com.storagemanager.storage_management.security.UnitScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
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
    private final UnitScope unitScope;
    private final InvoiceService invoices;

    /**
     * Cada cobro lleva su unidad, así que el ámbito se aplica directamente sobre
     * ella: se ven los de las unidades del usuario y ningún otro.
     */
    public List<Payment> getAllPayments() {
        return unitScope.filterByUnit(paymentRepository.findAll(), Payment::getStorageUnit);
    }

    public Payment getPaymentById(Long id) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found with id: " + id));
        unitScope.requireAccessible(payment.getStorageUnit());
        return payment;
    }

    public List<Payment> getPaymentsByStatus(PaymentStatus status) {
        return unitScope.filterByUnit(paymentRepository.findByStatus(status), Payment::getStorageUnit);
    }

    public List<Payment> getPaymentsByMonthYear(Integer year, Integer month) {
        return unitScope.filterByUnit(
                paymentRepository.findByBillingPeriodYearAndBillingPeriodMonth(year, month), Payment::getStorageUnit);
    }

    public List<Payment> getPaymentsByRental(Long rentalAgreementId) {
        return unitScope.filterByUnit(
                paymentRepository.findByRentalAgreementId(rentalAgreementId), Payment::getStorageUnit);
    }

    public List<Payment> getPaymentsByClient(Long clientId) {
        return unitScope.filterByUnit(paymentRepository.findByClientId(clientId), Payment::getStorageUnit);
    }

    public List<Payment> getPaymentsByStorageUnit(Long unitId) {
        unitScope.requireAccessible(unitId);
        return paymentRepository.findByStorageUnitId(unitId);
    }

    @Transactional
    public Payment createPayment(PaymentRequest request) {
        RentalAgreement agreement = rentalAgreementRepository.findById(request.getRentalAgreementId())
                .orElseThrow(() -> new ResourceNotFoundException("Rental agreement not found with id: " + request.getRentalAgreementId()));
        // El cobro es de la unidad del contrato: tiene que estar en el ámbito.
        unitScope.requireAccessible(agreement.getStorageUnit());

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

        return collected(paymentRepository.save(payment));
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

        return collected(paymentRepository.save(payment));
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
        return collected(paymentRepository.save(payment));
    }

    /**
     * Lo que pasa después de guardar un cobro: si el contrato está marcado para
     * facturar, sale su factura. Va por aquí y no en cada método para que no se
     * quede ningún camino fuera —cobro rápido, corrección a mano, alta directa—:
     * facturar depende de que haya entrado dinero, no de por dónde se registró.
     * <p>
     * Se hace cuando la transacción del cobro ya ha confirmado, y no dentro de
     * ella, a propósito: emitir la factura escribe en la base y en el almacén de
     * ficheros, y si eso fallara dentro de la misma transacción se llevaría el
     * cobro por delante —aunque el error se capture, la transacción ya estaría
     * marcada para deshacerse—. Registrar el dinero recibido es lo que no se
     * puede perder; la factura, si falla, se pide luego a mano.
     */
    private Payment collected(Payment payment) {
        Long id = payment.getId();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            invoices.issueIfEnabled(id);
            return payment;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                invoices.issueIfEnabled(id);
            }
        });
        return payment;
    }

    /**
     * Corrige a mano la mensualidad de un contrato: fija lo que se debe y lo que
     * se ha cobrado de ese mes, o lo marca como no cobrable. Si el mes todavía no
     * tenía fila de cobro (el caso normal de un vencido, que sólo existe deducido
     * del contrato) se le crea una; si ya la tenía, se corrige.
     * <p>
     * Un mes no cobrable se guarda como cobro anulado a cero, que es lo que
     * {@link BillingService} lee para dejarlo fuera de vencidos y de lo esperado.
     */
    @Transactional
    public Payment adjustCharge(Long rentalAgreementId, Integer year, Integer month, ChargeAdjustmentRequest request) {
        if (year == null || month == null || month < 1 || month > 12) {
            throw new BadRequestException("Invalid billing period: " + month + "/" + year);
        }
        RentalAgreement agreement = rentalAgreementRepository.findById(rentalAgreementId)
                .orElseThrow(() -> new ResourceNotFoundException("Rental agreement not found with id: " + rentalAgreementId));
        // El cobro es de la unidad del contrato: tiene que estar en el ámbito.
        unitScope.requireAccessible(agreement.getStorageUnit());

        BigDecimal due = request.isWaived() ? BigDecimal.ZERO : request.getAmountDue();
        BigDecimal paid = request.isWaived() || request.getAmountPaid() == null
                ? BigDecimal.ZERO : request.getAmountPaid();
        // Cobrar de más se admite (a veces la transferencia llega redondeada); el
        // cargo del mes ya deja el saldo en cero en vez de en negativo.

        Payment payment = paymentRepository
                .findByRentalAgreementIdAndBillingPeriodYearAndBillingPeriodMonth(agreement.getId(), year, month)
                .orElseGet(() -> Payment.builder()
                        .rentalAgreement(agreement)
                        .storageUnit(agreement.getStorageUnit())
                        .client(agreement.getClient())
                        .billingPeriodYear(year)
                        .billingPeriodMonth(month)
                        .build());

        payment.setAmountDue(due);
        payment.setAmountPaid(paid);
        payment.setStatus(request.isWaived() ? PaymentStatus.CANCELLED : PaymentStatus.PAID);
        // Sin dinero recibido no hay fecha ni método de cobro que guardar
        payment.setPaymentDate(paid.signum() > 0 ? request.getPaymentDate() : null);
        payment.setPaymentMethod(paid.signum() > 0 ? request.getPaymentMethod() : null);
        payment.setTransactionReference(request.getTransactionReference());
        payment.setNotes(request.getNotes());
        if (request.getDueDate() != null) {
            payment.setDueDate(request.getDueDate());
        } else if (payment.getDueDate() == null) {
            payment.setDueDate(billingDate(agreement, year, month));
        }

        return collected(paymentRepository.save(payment));
    }

    /**
     * Deshace la corrección de un mes: borrada su fila, el cargo vuelve a ser el
     * que dice el contrato. No encontrar nada que borrar no es un error, porque
     * el mes ya está como debía.
     */
    @Transactional
    public void clearChargeAdjustment(Long rentalAgreementId, Integer year, Integer month) {
        paymentRepository
                .findByRentalAgreementIdAndBillingPeriodYearAndBillingPeriodMonth(rentalAgreementId, year, month)
                .ifPresent(payment -> {
                    unitScope.requireAccessible(payment.getStorageUnit());
                    paymentRepository.delete(payment);
                });
    }

    /** El día de cobro pactado de ese mes, recortado al último día (febrero, día 31...). */
    private static LocalDate billingDate(RentalAgreement agreement, int year, int month) {
        YearMonth ym = YearMonth.of(year, month);
        int day = agreement.getBillingDayOfMonth() != null ? agreement.getBillingDayOfMonth() : 1;
        return ym.atDay(Math.min(Math.max(day, 1), ym.lengthOfMonth()));
    }

    @Transactional
    public void deletePayment(Long id) {
        Payment payment = getPaymentById(id);
        paymentRepository.delete(payment);
    }
}
