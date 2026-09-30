package com.storagemanager.storage_management;

import com.storagemanager.storage_management.dto.TerminationRequest;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.enums.RentalStatus;
import com.storagemanager.storage_management.model.enums.UnitStatus;
import com.storagemanager.storage_management.repository.RentalAgreementRepository;
import com.storagemanager.storage_management.repository.StorageUnitRepository;
import com.storagemanager.storage_management.service.RentalAgreementService;
import com.storagemanager.storage_management.service.RentalClosingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Finalizar un contrato: la baja dada con antelación sigue en vigor hasta su
 * fecha y se cierra sola después, y lo que se decide sobre la fianza no puede
 * ser más de lo que se entregó.
 * <p>
 * Cada prueba deja el contrato como estaba -en vigor, sin fecha de fin- con
 * {@code reactivate}, que es justo la operación que lo deshace.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class ScheduledTerminationTest {

    @Autowired private RentalAgreementService rentals;
    @Autowired private RentalClosingService closing;
    @Autowired private RentalAgreementRepository rentalRepository;
    @Autowired private StorageUnitRepository units;

    /** Un contrato en vigor, sin fecha de fin y con fianza: el caso de todos los días. */
    private RentalAgreement openRentalWithDeposit() {
        return rentalRepository.findByStatus(RentalStatus.ACTIVE).stream()
                .filter(r -> r.getEndDate() == null)
                .filter(r -> r.getSecurityDeposit() != null && r.getSecurityDeposit().signum() > 0)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no hay contratos en vigor con fianza en los datos sembrados"));
    }

    private RentalAgreement reload(RentalAgreement rental) {
        return rentalRepository.findById(rental.getId()).orElseThrow();
    }

    @Test
    void aNoticeForLaterKeepsTheContractInForceUntilItsDate() {
        RentalAgreement rental = openRentalWithDeposit();
        UnitStatus unitBefore = rental.getStorageUnit().getStatus();
        LocalDate leaving = LocalDate.now().plusMonths(2);
        try {
            TerminationRequest request = new TerminationRequest();
            request.setTerminationDate(leaving);
            rentals.terminateAgreement(rental.getId(), request);

            RentalAgreement scheduled = reload(rental);
            assertEquals(RentalStatus.ACTIVE, scheduled.getStatus(), "con la baja para dentro de dos meses sigue en vigor");
            assertEquals(leaving, scheduled.getEndDate());
            assertEquals(LocalDate.now(), scheduled.getTerminationNoticeDate());
            assertTrue(scheduled.isTerminationScheduled());
            assertEquals(unitBefore, units.findById(rental.getStorageUnit().getId()).orElseThrow().getStatus(),
                    "la unidad no se libera hasta que se vaya");

            // Todavía no toca: el cierre diario no lo cierra.
            closing.closeDue();
            assertEquals(RentalStatus.ACTIVE, reload(rental).getStatus());

            // Llega la fecha y pasa: el cierre diario lo cierra y libera la unidad.
            scheduled.setEndDate(LocalDate.now().minusDays(1));
            rentalRepository.save(scheduled);
            closing.closeDue();
            assertEquals(RentalStatus.TERMINATED, reload(rental).getStatus());
            assertEquals(UnitStatus.AVAILABLE, units.findById(rental.getStorageUnit().getId()).orElseThrow().getStatus());
        } finally {
            rentals.reactivate(rental.getId());
        }
        RentalAgreement restored = reload(rental);
        assertEquals(RentalStatus.ACTIVE, restored.getStatus());
        assertNull(restored.getEndDate());
        assertNull(restored.getTerminationNoticeDate());
    }

    @Test
    void cancellingTheNoticeLeavesTheContractAsItWas() {
        RentalAgreement rental = openRentalWithDeposit();
        TerminationRequest request = new TerminationRequest();
        request.setTerminationDate(LocalDate.now().plusMonths(1));
        rentals.terminateAgreement(rental.getId(), request);

        rentals.reactivate(rental.getId());

        RentalAgreement restored = reload(rental);
        assertEquals(RentalStatus.ACTIVE, restored.getStatus());
        assertNull(restored.getEndDate(), "anular la baja quita la fecha de fin");
        assertNull(restored.getTerminationNoticeDate());
        assertFalse(restored.isTerminationScheduled());
    }

    @Test
    void moreThanTheDepositCannotBeReturned() {
        RentalAgreement rental = openRentalWithDeposit();
        TerminationRequest request = new TerminationRequest();
        request.setTerminationDate(LocalDate.now().plusMonths(1));
        request.setDepositReturned(true);
        request.setDepositReturnedAmount(rental.getSecurityDeposit().add(BigDecimal.ONE));

        assertThrows(BadRequestException.class, () -> rentals.terminateAgreement(rental.getId(), request));

        RentalAgreement untouched = reload(rental);
        assertEquals(RentalStatus.ACTIVE, untouched.getStatus());
        assertNull(untouched.getEndDate(), "un cierre rechazado no puede dejar la fecha de fin puesta");
    }

    @Test
    void aPartialReturnIsRecordedWithItsReason() {
        RentalAgreement rental = openRentalWithDeposit();
        BigDecimal half = rental.getSecurityDeposit().divide(new BigDecimal("2"), 2, java.math.RoundingMode.HALF_UP);
        try {
            TerminationRequest request = new TerminationRequest();
            request.setTerminationDate(LocalDate.now().plusMonths(1));
            request.setDepositReturned(true);
            request.setDepositReturnedAmount(half);
            request.setDepositReturnNotes("  Pintura de una pared  ");
            rentals.terminateAgreement(rental.getId(), request);

            RentalAgreement closed = reload(rental);
            assertTrue(closed.getDepositReturned());
            assertEquals(0, closed.getDepositReturnedAmount().compareTo(half));
            assertEquals("Pintura de una pared", closed.getDepositReturnNotes());
        } finally {
            rentals.reactivate(rental.getId());
        }
        assertNull(reload(rental).getDepositReturned(), "anular la baja olvida lo decidido sobre la fianza");
    }
}
