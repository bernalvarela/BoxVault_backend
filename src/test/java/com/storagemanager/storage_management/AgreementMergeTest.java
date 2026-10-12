package com.storagemanager.storage_management;

import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.model.BankImport;
import com.storagemanager.storage_management.model.BankImportLine;
import com.storagemanager.storage_management.model.BankImportProfile;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.enums.BankLineAction;
import com.storagemanager.storage_management.model.enums.BankProfileContext;
import com.storagemanager.storage_management.model.enums.RentalStatus;
import com.storagemanager.storage_management.repository.BankImportLineRepository;
import com.storagemanager.storage_management.repository.BankImportProfileRepository;
import com.storagemanager.storage_management.repository.BankImportRepository;
import com.storagemanager.storage_management.repository.RentalAgreementRepository;
import com.storagemanager.storage_management.service.RentalAgreementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unir un contrato duplicado con el bueno: lo que colgaba del duplicado pasa al
 * bueno y el duplicado se borra.
 * <p>
 * Nació de un fallo real: un movimiento del banco casado con el duplicado
 * impedía borrarlo (fk_bank_import_lines_rental) y la unión entera se caía.
 * Cada prueba crea su propio duplicado, sin cobros, en la unidad de un contrato
 * sembrado, y borra lo que crea al terminar.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AgreementMergeTest {

    @Autowired private RentalAgreementService rentals;
    @Autowired private RentalAgreementRepository rentalRepository;
    @Autowired private BankImportProfileRepository profiles;
    @Autowired private BankImportRepository imports;
    @Autowired private BankImportLineRepository lines;

    private BankImport bankImport;
    private BankImportProfile profile;
    private RentalAgreement duplicate;

    @AfterEach
    void cleanUp() {
        if (bankImport != null) imports.deleteById(bankImport.getId()); // sus líneas se van con él
        if (profile != null) profiles.deleteById(profile.getId());
        if (duplicate != null && rentalRepository.existsById(duplicate.getId())) {
            rentalRepository.deleteById(duplicate.getId());
        }
    }

    /** Un contrato en vigor de los sembrados: el bueno. */
    private RentalAgreement target() {
        return rentalRepository.findByStatus(RentalStatus.ACTIVE).stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("no hay contratos en vigor en los datos sembrados"));
    }

    /** Un segundo contrato de la misma unidad y el mismo titular, ya terminado y sin cobros. */
    private RentalAgreement duplicateOf(RentalAgreement target) {
        duplicate = rentalRepository.save(RentalAgreement.builder()
                .agreementNumber("DUP-" + System.nanoTime())
                .storageUnit(target.getStorageUnit())
                .client(target.getClient())
                .startDate(LocalDate.of(2020, 1, 1))
                .endDate(LocalDate.of(2020, 12, 31))
                .monthlyRent(target.getMonthlyRent())
                .status(RentalStatus.TERMINATED)
                .build());
        return duplicate;
    }

    /** Un movimiento de un extracto casado con ese contrato, como lo deja el importador. */
    private BankImportLine bankLineFor(RentalAgreement agreement) {
        profile = profiles.save(BankImportProfile.builder()
                .name("Prueba unión " + System.nanoTime())
                .context(BankProfileContext.PROPIETARIOS)
                .dateColumn(1)
                .conceptColumn(2)
                .amountColumn(3)
                .build());
        bankImport = imports.save(BankImport.builder().profile(profile).fileName("extracto.csv").build());
        return lines.save(BankImportLine.builder()
                .bankImport(bankImport)
                .lineNumber(1)
                .date(LocalDate.of(2020, 3, 5))
                .concept("TRANSFERENCIA ALQUILER TRASTERO")
                .amount(agreement.getMonthlyRent())
                .fingerprint("merge-test-" + System.nanoTime())
                .action(BankLineAction.RENT_PAYMENT)
                .rentalAgreement(agreement)
                .storageUnit(agreement.getStorageUnit())
                .periodYear(2020)
                .periodMonth(3)
                .build());
    }

    @Test
    void bankMovementsOfTheDuplicateMoveToTheGoodContract() {
        RentalAgreement target = target();
        RentalAgreement source = duplicateOf(target);
        BankImportLine line = bankLineFor(source);

        RentalAgreement merged = assertDoesNotThrow(() -> rentals.absorb(target.getId(), source.getId()),
                "un movimiento del banco casado con el duplicado no puede impedir la unión");

        assertEquals(target.getId(), merged.getId());
        assertFalse(rentalRepository.existsById(source.getId()), "el duplicado se borra");
        BankImportLine moved = lines.findById(line.getId()).orElseThrow();
        assertEquals(target.getId(), moved.getRentalAgreement().getId(), "el movimiento pasa al contrato bueno");
        assertEquals(BankLineAction.RENT_PAYMENT, moved.getAction(), "lo demás del movimiento no cambia");
        assertEquals(0, moved.getAmount().compareTo(target.getMonthlyRent()));
    }

    @Test
    void aContractCannotBeMergedWithItself() {
        RentalAgreement target = target();
        assertThrows(BadRequestException.class, () -> rentals.absorb(target.getId(), target.getId()));
        assertTrue(rentalRepository.existsById(target.getId()));
    }
}
