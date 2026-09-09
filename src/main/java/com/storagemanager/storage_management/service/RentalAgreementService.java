package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.dto.RentalAgreementRequest;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.Client;
import com.storagemanager.storage_management.model.Payment;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.model.enums.RentalStatus;
import com.storagemanager.storage_management.model.enums.UnitStatus;
import com.storagemanager.storage_management.repository.ClientRepository;
import com.storagemanager.storage_management.repository.PaymentRepository;
import com.storagemanager.storage_management.repository.RentalAgreementRepository;
import com.storagemanager.storage_management.repository.StorageUnitRepository;
import com.storagemanager.storage_management.security.UnitScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RentalAgreementService {

    private final RentalAgreementRepository rentalAgreementRepository;
    private final StorageUnitRepository storageUnitRepository;
    private final ClientRepository clientRepository;
    private final PaymentRepository paymentRepository;
    private final UnitScope unitScope;

    /** Un contrato es de la unidad que alquila: se ve si esa unidad es del usuario. */
    public List<RentalAgreement> getAllAgreements() {
        return unitScope.filterByUnit(rentalAgreementRepository.findAll(), RentalAgreement::getStorageUnit);
    }

    public List<RentalAgreement> getActiveAgreements() {
        return unitScope.filterByUnit(
                rentalAgreementRepository.findByStatus(RentalStatus.ACTIVE), RentalAgreement::getStorageUnit);
    }

    public RentalAgreement getAgreementById(Long id) {
        RentalAgreement agreement = rentalAgreementRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Rental agreement not found with id: " + id));
        unitScope.requireAccessible(agreement.getStorageUnit());
        return agreement;
    }

    /** Contracts of a client as main or second tenant. */
    public List<RentalAgreement> getAgreementsByClient(Long clientId) {
        return unitScope.filterByUnit(
                rentalAgreementRepository.findByClientIdOrCoClientId(clientId, clientId),
                RentalAgreement::getStorageUnit);
    }

    public List<RentalAgreement> getAgreementsByStorageUnit(Long storageUnitId) {
        unitScope.requireAccessible(storageUnitId);
        return rentalAgreementRepository.findByStorageUnitId(storageUnitId);
    }

    @Transactional
    public RentalAgreement createAgreement(RentalAgreementRequest request) {
        StorageUnit unit = storageUnitRepository.findById(request.getStorageUnitId())
                .orElseThrow(() -> new ResourceNotFoundException("Storage unit not found with id: " + request.getStorageUnitId()));
        // Alquilar una unidad que no es tuya, no.
        unitScope.requireAccessible(unit);

        if (unit.getStatus() == UnitStatus.OCCUPIED) {
            throw new BadRequestException("Storage unit " + unit.getUnitNumber() + " is already occupied");
        }
        if (unit.getStatus() == UnitStatus.MAINTENANCE) {
            throw new BadRequestException("Storage unit " + unit.getUnitNumber() + " is currently under maintenance");
        }

        Client client = clientRepository.findById(request.getClientId())
                .orElseThrow(() -> new ResourceNotFoundException("Client not found with id: " + request.getClientId()));
        Client coClient = resolveCoClient(request.getCoClientId(), client);

        String agreementNumber = "RNT-" + LocalDate.now().getYear() + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        RentalAgreement agreement = RentalAgreement.builder()
                .agreementNumber(agreementNumber)
                .storageUnit(unit)
                .client(client)
                .coClient(coClient)
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .billingDayOfMonth(request.getBillingDayOfMonth() != null ? request.getBillingDayOfMonth() : 1)
                .monthlyRent(request.getMonthlyRent())
                .securityDeposit(request.getSecurityDeposit())
                .depositPaid(request.getDepositPaid() != null ? request.getDepositPaid() : false)
                .status(RentalStatus.ACTIVE)
                .autoRenew(request.getAutoRenew() != null ? request.getAutoRenew() : true)
                .notes(request.getNotes())
                .build();

        // Mark unit as occupied
        unit.setStatus(UnitStatus.OCCUPIED);
        storageUnitRepository.save(unit);

        return rentalAgreementRepository.save(agreement);
    }

    @Transactional
    public RentalAgreement updateAgreement(Long id, RentalAgreementRequest request) {
        RentalAgreement agreement = getAgreementById(id);

        if (request.getClientId() != null && !request.getClientId().equals(agreement.getClient().getId())) {
            Client previous = agreement.getClient();
            Client corrected = clientRepository.findById(request.getClientId())
                    .orElseThrow(() -> new ResourceNotFoundException("Client not found with id: " + request.getClientId()));
            agreement.setClient(corrected);

            // Los cobros llevan su propio cliente, y BillingService da preferencia
            // a ése sobre el del contrato: si no se cambian también, corregir el
            // titular dejaría las mensualidades ya registradas a nombre del
            // anterior —en el registro de cobros y en la ficha de los dos—.
            // Cambiar el titular de un contrato es corregir un error de captura,
            // no traspasarlo: sus meses siempre fueron de quien de verdad alquila.
            List<Payment> ofAgreement = paymentRepository.findByRentalAgreementId(id);
            ofAgreement.forEach(payment -> payment.setClient(corrected));
            paymentRepository.saveAll(ofAgreement);

            log.info("Contrato {}: titular corregido de '{}' a '{}'; {} cobro(s) reasignado(s)",
                    agreement.getAgreementNumber(), previous.getFullName(), corrected.getFullName(),
                    ofAgreement.size());
        }
        agreement.setCoClient(resolveCoClient(request.getCoClientId(), agreement.getClient()));
        agreement.setStartDate(request.getStartDate());
        agreement.setEndDate(request.getEndDate());
        agreement.setBillingDayOfMonth(request.getBillingDayOfMonth() != null ? request.getBillingDayOfMonth() : 1);
        agreement.setMonthlyRent(request.getMonthlyRent());
        agreement.setSecurityDeposit(request.getSecurityDeposit());
        if (request.getDepositPaid() != null) {
            agreement.setDepositPaid(request.getDepositPaid());
        }
        if (request.getAutoRenew() != null) {
            agreement.setAutoRenew(request.getAutoRenew());
        }
        agreement.setNotes(request.getNotes());

        return rentalAgreementRepository.save(agreement);
    }

    /** The optional second tenant: must exist and differ from the main tenant. */
    private Client resolveCoClient(Long coClientId, Client client) {
        if (coClientId == null) return null;
        if (coClientId.equals(client.getId())) {
            throw new BadRequestException("The second tenant must be a different client");
        }
        return clientRepository.findById(coClientId)
                .orElseThrow(() -> new ResourceNotFoundException("Client not found with id: " + coClientId));
    }

    @Transactional
    public RentalAgreement terminateAgreement(Long id, LocalDate terminationDate) {
        RentalAgreement agreement = getAgreementById(id);
        agreement.setStatus(RentalStatus.TERMINATED);
        agreement.setEndDate(terminationDate != null ? terminationDate : LocalDate.now());

        // Free up the storage unit if no other active agreement exists
        StorageUnit unit = agreement.getStorageUnit();
        unit.setStatus(UnitStatus.AVAILABLE);
        storageUnitRepository.save(unit);

        return rentalAgreementRepository.save(agreement);
    }
}
