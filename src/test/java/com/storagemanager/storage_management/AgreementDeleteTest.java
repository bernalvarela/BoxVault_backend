package com.storagemanager.storage_management;

import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.model.BankImport;
import com.storagemanager.storage_management.model.BankImportLine;
import com.storagemanager.storage_management.model.BankImportProfile;
import com.storagemanager.storage_management.model.Client;
import com.storagemanager.storage_management.model.Payment;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.enums.BankLineAction;
import com.storagemanager.storage_management.model.enums.BankLineStatus;
import com.storagemanager.storage_management.model.enums.BankProfileContext;
import com.storagemanager.storage_management.model.enums.PaymentStatus;
import com.storagemanager.storage_management.model.enums.RentalStatus;
import com.storagemanager.storage_management.repository.BankImportLineRepository;
import com.storagemanager.storage_management.repository.BankImportProfileRepository;
import com.storagemanager.storage_management.repository.BankImportRepository;
import com.storagemanager.storage_management.repository.ClientRepository;
import com.storagemanager.storage_management.repository.PaymentRepository;
import com.storagemanager.storage_management.repository.RentalAgreementRepository;
import com.storagemanager.storage_management.service.ClientService;
import com.storagemanager.storage_management.service.RentalAgreementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Borrar un contrato creado por error, con sus mensualidades, y después a su
 * titular. Cada prueba crea su propio cliente y contrato -terminado, para no
 * tocar el estado de la unidad- en la unidad de un contrato sembrado, y borra
 * lo que quede al terminar.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AgreementDeleteTest {

    @Autowired private RentalAgreementService rentals;
    @Autowired private ClientService clientService;
    @Autowired private RentalAgreementRepository rentalRepository;
    @Autowired private PaymentRepository payments;
    @Autowired private ClientRepository clients;
    @Autowired private BankImportProfileRepository profiles;
    @Autowired private BankImportRepository imports;
    @Autowired private BankImportLineRepository lines;

    private Client client;
    private RentalAgreement wrong;
    private BankImport bankImport;
    private BankImportProfile profile;

    @AfterEach
    void cleanUp() {
        if (bankImport != null) imports.deleteById(bankImport.getId());
        if (profile != null) profiles.deleteById(profile.getId());
        if (wrong != null && rentalRepository.existsById(wrong.getId())) {
            payments.deleteAll(payments.findByRentalAgreementId(wrong.getId()));
            rentalRepository.deleteById(wrong.getId());
        }
        if (client != null && clients.existsById(client.getId())) clients.deleteById(client.getId());
    }

    /** Un contrato que no debió crearse, de un cliente nuevo, con una mensualidad cobrada. */
    private RentalAgreement wrongContract() {
        RentalAgreement seeded = rentalRepository.findByStatus(RentalStatus.ACTIVE).stream().findFirst()
                .orElseThrow(() -> new AssertionError("no hay contratos en vigor en los datos sembrados"));
        long n = System.nanoTime();
        client = clients.save(Client.builder()
                .fullName("Cliente erróneo " + n)
                .email("erroneo" + n + "@example.com")
                .phone("600000000")
                .build());
        wrong = rentalRepository.save(RentalAgreement.builder()
                .agreementNumber("ERR-" + n)
                .storageUnit(seeded.getStorageUnit())
                .client(client)
                .startDate(LocalDate.of(2019, 1, 1))
                .endDate(LocalDate.of(2019, 12, 31))
                .monthlyRent(new BigDecimal("40.00"))
                .status(RentalStatus.TERMINATED)
                .build());
        payments.save(Payment.builder()
                .rentalAgreement(wrong)
                .storageUnit(wrong.getStorageUnit())
                .client(client)
                .billingPeriodYear(2019)
                .billingPeriodMonth(3)
                .amountDue(new BigDecimal("40.00"))
                .amountPaid(new BigDecimal("40.00"))
                .dueDate(LocalDate.of(2019, 3, 1))
                .status(PaymentStatus.PAID)
                .build());
        return wrong;
    }

    /** Un movimiento del banco aplicado contra ese contrato y su cobro. */
    private BankImportLine appliedBankLine(RentalAgreement agreement, Long paymentId) {
        profile = profiles.save(BankImportProfile.builder()
                .name("Prueba borrado " + System.nanoTime())
                .context(BankProfileContext.PROPIETARIOS)
                .dateColumn(1)
                .conceptColumn(2)
                .amountColumn(3)
                .build());
        bankImport = imports.save(BankImport.builder().profile(profile).fileName("extracto.csv")
                .status(BankImport.APPLIED).build());
        return lines.save(BankImportLine.builder()
                .bankImport(bankImport)
                .lineNumber(1)
                .date(LocalDate.of(2019, 3, 4))
                .concept("TRANSFERENCIA ALQUILER")
                .amount(new BigDecimal("40.00"))
                .fingerprint("delete-test-" + System.nanoTime())
                .action(BankLineAction.RENT_PAYMENT)
                .status(BankLineStatus.APPLIED)
                .rentalAgreement(agreement)
                .paymentId(paymentId)
                .periodYear(2019)
                .periodMonth(3)
                .build());
    }

    @Test
    void deletingAContractTakesItsPaymentsAndFreesItsBankMovements() {
        RentalAgreement agreement = wrongContract();
        Long paymentId = payments.findByRentalAgreementId(agreement.getId()).getFirst().getId();
        BankImportLine line = appliedBankLine(agreement, paymentId);

        rentals.deleteAgreement(agreement.getId());

        assertFalse(rentalRepository.existsById(agreement.getId()), "el contrato se borra");
        assertFalse(payments.existsById(paymentId), "y sus mensualidades con él");

        BankImportLine freed = lines.findById(line.getId()).orElseThrow();
        assertNull(freed.getRentalAgreement(), "el movimiento ya no apunta al contrato borrado");
        assertNull(freed.getPaymentId(), "ni a su cobro");
        assertEquals(BankLineStatus.PENDING, freed.getStatus(), "vuelve a pendiente para asignarlo al bueno");
        assertEquals(BankImport.REVIEW, imports.findById(bankImport.getId()).orElseThrow().getStatus(),
                "y su extracto vuelve a revisión");
    }

    @Test
    void aClientCanBeDeletedOnceTheirWrongContractIsGone() {
        RentalAgreement agreement = wrongContract();

        assertThrows(BadRequestException.class, () -> clientService.deleteClient(client.getId()),
                "mientras firme un contrato no se borra");
        assertTrue(clients.existsById(client.getId()));

        rentals.deleteAgreement(agreement.getId());
        clientService.deleteClient(client.getId());

        assertFalse(clients.existsById(client.getId()));
    }
}
