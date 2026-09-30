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
 * En Galicia la fianza en metálico de los pisos se deposita en el Instituto
 * Galego da Vivenda e Solo en el plazo de un mes desde la firma; la de los
 * trasteros y locales no. No depositarla se sanciona con una multa no inferior al
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
     * Guarda todo lo de la fianza: el total entregado y cuánto de él es
     * garantía, si está cobrada, y el depósito en el IGVS con su devolución.
     * Se manda todo cada vez; sin fecha de depósito se borra el apunte del IGVS,
     * que es como se corrige uno hecho en el alquiler que no era.
     */
    @Transactional
    public RentalAgreement update(Long rentalId, DepositLodgingRequest request) {
        RentalAgreement rental = rentals.getAgreementById(rentalId);
        LocalDate today = LocalDate.now();

        applyAmounts(rental, request);

        // Borrar un apunte siempre se puede: es como se arregla uno hecho en la
        // unidad que no era. Apuntar, solo en los pisos.
        if (request.getLodgedOn() != null) requireDwelling(rental);

        if (request.getLodgedOn() == null) {
            if (rental.getDepositLodgedOn() != null) {
                log.info("Borrado el depósito en el IGVS de la fianza del contrato {}", rental.getAgreementNumber());
            }
            clear(rental);
            return rentalRepository.save(rental);
        }

        LocalDate lodgedOn = request.getLodgedOn();
        if (lodgedOn.isAfter(today)) {
            throw new BadRequestException("La fecha del depósito no puede ser posterior a hoy");
        }
        // Sin importe, lo normal: la fianza legal, una mensualidad. El resto de lo
        // entregado es garantía adicional y no se deposita.
        BigDecimal amount = request.getLodgedAmount() != null ? request.getLodgedAmount() : lodgeAmount(rental);
        if (amount == null || amount.signum() <= 0) {
            throw new BadRequestException("Indica cuánto se depositó en el IGVS");
        }
        BigDecimal deposit = rental.getSecurityDeposit();
        if (deposit != null && amount.compareTo(deposit) > 0) {
            throw new BadRequestException("No se pueden haber depositado " + amount + " €: el inquilino entregó "
                    + deposit + " € entre fianza y garantía");
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
        requireDwelling(rental);
        StorageUnit unit = rental.getStorageUnit();
        InvoiceIssuer.Issuer issuer = issuers.forUnit(unit);

        VatUtils.Breakdown rent = VatUtils.breakdown(rental.getMonthlyRent(), unit.isVatApplicable());
        BigDecimal deposit = rental.getSecurityDeposit() == null ? BigDecimal.ZERO : rental.getSecurityDeposit();
        BigDecimal lodge = lodgeAmount(rental);

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
                unit.getName(),
                unit.getLocation(),
                unit.getCadastralReference(),
                "Vivienda",
                rental.getAgreementNumber(),
                rental.getStartDate(),
                rental.getEndDate(),
                rent.total(),
                rent.base(),
                deposit,
                lodge,
                deposit.subtract(lodge).max(BigDecimal.ZERO),
                rental.getStartDate() == null ? null : rental.getStartDate().plusMonths(1));
    }

    /**
     * Lo que se deposita en el IGVS: la fianza legal. Si ya se separó la
     * garantía, es lo que queda de lo entregado; si no, se supone una
     * mensualidad de renta (art. 36 LAU), o lo entregado si fue menos.
     */
    private static BigDecimal lodgeAmount(RentalAgreement rental) {
        if (rental.getGuaranteeDeposit() != null && rental.getLegalDeposit() != null) {
            return rental.getLegalDeposit().setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal rent = rental.getMonthlyRent() == null ? BigDecimal.ZERO : rental.getMonthlyRent();
        BigDecimal deposit = rental.getSecurityDeposit();
        BigDecimal lodge = deposit == null ? rent : rent.min(deposit);
        return lodge.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * El total entregado, la parte que es garantía y si está cobrada. La
     * garantía no puede ser más que el total, y en un trastero no hay garantía
     * aparte: todo es fianza.
     */
    private static void applyAmounts(RentalAgreement rental, DepositLodgingRequest request) {
        if (request.getSecurityDeposit() != null) {
            if (request.getSecurityDeposit().signum() < 0) {
                throw new BadRequestException("La fianza no puede ser negativa");
            }
            rental.setSecurityDeposit(request.getSecurityDeposit().setScale(2, RoundingMode.HALF_UP));
        }
        if (request.getDepositPaid() != null) rental.setDepositPaid(request.getDepositPaid());

        BigDecimal guarantee = request.getGuaranteeDeposit();
        if (guarantee != null && guarantee.signum() > 0) {
            BigDecimal total = rental.getSecurityDeposit() == null ? BigDecimal.ZERO : rental.getSecurityDeposit();
            if (guarantee.compareTo(total) > 0) {
                throw new BadRequestException("El depósito de garantía (" + guarantee + " €) no puede ser más que lo "
                        + "entregado en total (" + total + " €)");
            }
            rental.setGuaranteeDeposit(guarantee.setScale(2, RoundingMode.HALF_UP));
        } else {
            rental.setGuaranteeDeposit(null);
        }
    }

    /** Solo las fianzas de los pisos se depositan en el IGVS; las de los trasteros y locales, no. */
    private static void requireDwelling(RentalAgreement rental) {
        StorageUnit unit = rental.getStorageUnit();
        if (unit == null || unit.getKind() != UnitKind.APARTMENT) {
            throw new BadRequestException("La fianza de " + (unit == null ? "esta unidad" : unit.getName())
                    + " no se deposita en el IGVS: solo se depositan las de los pisos");
        }
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
