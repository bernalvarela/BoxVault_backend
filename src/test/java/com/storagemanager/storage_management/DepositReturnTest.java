package com.storagemanager.storage_management;

import com.storagemanager.storage_management.dto.DepositLodgingRequest;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.enums.RentalStatus;
import com.storagemanager.storage_management.repository.RentalAgreementRepository;
import com.storagemanager.storage_management.service.DepositLodgingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lo que se devuelve de la fianza, apuntado o corregido desde su pestaña después
 * de cerrar el contrato: cuánto, y por qué no entera. Cada prueba deja el
 * contrato como estaba.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class DepositReturnTest {

    @Autowired private DepositLodgingService deposits;
    @Autowired private RentalAgreementRepository rentals;

    private RentalAgreement withDeposit(RentalStatus status) {
        return rentals.findByStatus(status).stream()
                .filter(r -> r.getSecurityDeposit() != null && r.getSecurityDeposit().signum() > 0)
                .filter(r -> status != RentalStatus.ACTIVE || !r.isTerminationScheduled())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no hay contratos " + status + " con fianza en los datos sembrados"));
    }

    /** La petición con la fianza tal como está, para tocar sólo la devolución. */
    private static DepositLodgingRequest asItIs(RentalAgreement r) {
        DepositLodgingRequest request = new DepositLodgingRequest();
        request.setSecurityDeposit(r.getSecurityDeposit());
        request.setGuaranteeDeposit(r.getGuaranteeDeposit());
        request.setDepositPaid(r.getDepositPaid());
        request.setLodgedOn(r.getDepositLodgedOn());
        request.setLodgedAmount(r.getDepositLodgedAmount());
        request.setReference(r.getDepositLodgingReference());
        request.setReceiptDelivered(r.getDepositReceiptDelivered());
        request.setRefundRequestedOn(r.getDepositRefundRequestedOn());
        request.setRefundedOn(r.getDepositRefundedOn());
        request.setDepositReturned(r.getDepositReturned());
        request.setReturnedAmount(r.getDepositReturnedAmount());
        request.setReturnNotes(r.getDepositReturnNotes());
        return request;
    }

    @Test
    void aPartialReturnKeepsTheAmountAndTheReason() {
        RentalAgreement closed = withDeposit(RentalStatus.TERMINATED);
        DepositLodgingRequest original = asItIs(closed);
        BigDecimal half = closed.getSecurityDeposit().divide(BigDecimal.valueOf(2), 2, java.math.RoundingMode.HALF_UP);
        try {
            DepositLodgingRequest request = asItIs(closed);
            request.setDepositReturned(true);
            request.setReturnedAmount(half);
            request.setReturnNotes("  Cubre la última mensualidad  ");
            RentalAgreement saved = deposits.update(closed.getId(), request);

            assertEquals(Boolean.TRUE, saved.getDepositReturned());
            assertEquals(0, half.compareTo(saved.getDepositReturnedAmount()));
            assertEquals("Cubre la última mensualidad", saved.getDepositReturnNotes());

            DepositLodgingRequest tooMuch = asItIs(saved);
            tooMuch.setReturnedAmount(closed.getSecurityDeposit().add(BigDecimal.ONE));
            assertThrows(BadRequestException.class, () -> deposits.update(closed.getId(), tooMuch));

            DepositLodgingRequest retained = asItIs(saved);
            retained.setDepositReturned(false);
            retained.setReturnedAmount(null);
            RentalAgreement kept = deposits.update(closed.getId(), retained);
            assertEquals(0, BigDecimal.ZERO.compareTo(kept.getDepositReturnedAmount()), "retenida entera: cero devuelto");
        } finally {
            deposits.update(closed.getId(), original);
        }
    }

    @Test
    void aContractInForceCannotRecordAReturn() {
        RentalAgreement active = withDeposit(RentalStatus.ACTIVE);
        DepositLodgingRequest request = asItIs(active);
        request.setDepositReturned(true);
        assertThrows(BadRequestException.class, () -> deposits.update(active.getId(), request));
    }
}
