package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.config.VatUtils;
import com.storagemanager.storage_management.dto.DepositFormDTO;
import com.storagemanager.storage_management.dto.DepositLodgingRequest;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.model.Client;
import com.storagemanager.storage_management.model.Owner;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.model.enums.RentalStatus;
import com.storagemanager.storage_management.model.enums.UnitKind;
import com.storagemanager.storage_management.repository.RentalAgreementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * La fianza depositada en el IGVS: apuntar el depósito y su devolución, y
 * preparar los datos que pide el formulario de la Xunta.
 * <p>
 * En Galicia la fianza en metálico se deposita en el Instituto Galego da
 * Vivenda e Solo en el plazo de un mes desde la firma, en vivienda y en uso
 * distinto de vivienda. No depositarla se sanciona con una multa no inferior al
 * doble del depósito; regularizarla antes de que lo requieran evita la sanción.
 * El trámite es manual y no tiene API, así que la aplicación no lo hace: lo
 * apunta, y con eso la bandeja de avisos sabe qué falta.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DepositLodgingService {

    private final RentalAgreementService rentals;
    private final RentalAgreementRepository rentalRepository;
    private final InvoiceIssuer issuers;

    /**
     * Apunta el depósito y su devolución. Se manda todo cada vez; sin fecha de
     * depósito se borra lo que hubiera, que es como se corrige un apunte hecho
     * en el alquiler que no era.
     */
    @Transactional
    public RentalAgreement update(Long rentalId, DepositLodgingRequest request) {
        RentalAgreement rental = rentals.getAgreementById(rentalId);
        LocalDate today = LocalDate.now();

        if (request.getLodgedOn() == null) {
            clear(rental);
            log.info("Borrado el depósito en el IGVS de la fianza del contrato {}", rental.getAgreementNumber());
            return rentalRepository.save(rental);
        }

        LocalDate lodgedOn = request.getLodgedOn();
        if (lodgedOn.isAfter(today)) {
            throw new BadRequestException("La fecha del depósito no puede ser posterior a hoy");
        }
        BigDecimal amount = request.getLodgedAmount() != null ? request.getLodgedAmount() : rental.getSecurityDeposit();
        if (amount == null || amount.signum() <= 0) {
            throw new BadRequestException("Indica cuánto se depositó en el IGVS");
        }

        LocalDate requested = request.getRefundRequestedOn();
        LocalDate refunded = request.getRefundedOn();
        if (requested != null) {
            if (rental.getStatus() == RentalStatus.ACTIVE && !rental.isTerminationScheduled()) {
                throw new BadRequestException("El contrato " + rental.getAgreementNumber()
                        + " sigue en vigor: la devolución de la fianza se pide al terminarlo");
            }
            if (requested.isBefore(lodgedOn)) {
                throw new BadRequestException("La devolución no se puede pedir antes de haber depositado la fianza");
            }
            if (requested.isAfter(today)) {
                throw new BadRequestException("La fecha en que se pidió la devolución no puede ser posterior a hoy");
            }
        }
        if (refunded != null) {
            if (requested == null) {
                throw new BadRequestException("Para apuntar el reintegro, apunta antes cuándo se pidió la devolución");
            }
            if (refunded.isBefore(requested) || refunded.isAfter(today)) {
                throw new BadRequestException("La fecha del reintegro tiene que estar entre la solicitud y hoy");
            }
        }

        rental.setDepositLodgedOn(lodgedOn);
        rental.setDepositLodgedAmount(amount.setScale(2, RoundingMode.HALF_UP));
        rental.setDepositLodgingReference(trimToNull(request.getReference(), 60));
        rental.setDepositReceiptDelivered(Boolean.TRUE.equals(request.getReceiptDelivered()));
        rental.setDepositRefundRequestedOn(requested);
        rental.setDepositRefundedOn(refunded);
        log.info("Apuntado el depósito en el IGVS de la fianza del contrato {} ({} €, {})",
                rental.getAgreementNumber(), amount, lodgedOn);
        return rentalRepository.save(rental);
    }

    /** Lo que pide el formulario VI436A, sacado del alquiler. */
    public DepositFormDTO form(Long rentalId) {
        RentalAgreement rental = rentals.getAgreementById(rentalId);
        StorageUnit unit = rental.getStorageUnit();
        InvoiceIssuer.Issuer issuer = issuers.forUnit(unit);

        boolean vatApplicable = unit != null && unit.isVatApplicable();
        VatUtils.Breakdown rent = VatUtils.breakdown(rental.getMonthlyRent(), vatApplicable);
        boolean dwelling = unit != null && unit.getKind() == UnitKind.APARTMENT;
        // Vivienda: una mensualidad. Uso distinto: dos, sin IVA (art. 36 LAU).
        int months = dwelling ? 1 : 2;

        List<DepositFormDTO.Person> owners = issuer.owners().stream()
                .map(DepositLodgingService::person)
                .toList();
        List<DepositFormDTO.Person> tenants = rental.tenants().stream()
                .map(DepositLodgingService::person)
                .toList();

        return new DepositFormDTO(
                new DepositFormDTO.Person(issuer.name(), issuer.taxId(), joinAddress(issuer.address(), issuer.city())),
                owners,
                tenants,
                unit == null ? null : unit.getName(),
                unit == null ? null : unit.getLocation(),
                unit == null ? null : unit.getCadastralReference(),
                dwelling ? "Vivienda" : "Uso distinto de vivienda",
                rental.getAgreementNumber(),
                rental.getStartDate(),
                rental.getEndDate(),
                rent.total(),
                rent.base(),
                rental.getSecurityDeposit(),
                months,
                rent.base().multiply(BigDecimal.valueOf(months)).setScale(2, RoundingMode.HALF_UP),
                rental.getStartDate() == null ? null : rental.getStartDate().plusMonths(1));
    }

    private static void clear(RentalAgreement rental) {
        rental.setDepositLodgedOn(null);
        rental.setDepositLodgedAmount(null);
        rental.setDepositLodgingReference(null);
        rental.setDepositReceiptDelivered(null);
        rental.setDepositRefundRequestedOn(null);
        rental.setDepositRefundedOn(null);
    }

    private static DepositFormDTO.Person person(Owner owner) {
        return new DepositFormDTO.Person(owner.getFullName(), owner.getDocumentId(),
                joinAddress(owner.getAddress(), owner.getCity()));
    }

    private static DepositFormDTO.Person person(Client client) {
        return new DepositFormDTO.Person(client.getFullName(), client.getDocumentId(), client.getAddress());
    }

    private static String joinAddress(String address, String city) {
        boolean hasAddress = address != null && !address.isBlank();
        boolean hasCity = city != null && !city.isBlank();
        if (hasAddress && hasCity) return address.trim() + ", " + city.trim();
        if (hasAddress) return address.trim();
        return hasCity ? city.trim() : null;
    }

    private static String trimToNull(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }
}
