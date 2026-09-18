package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.dto.RentalAgreementRequest;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.Client;
import com.storagemanager.storage_management.model.Payment;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.RentalDocument;
import com.storagemanager.storage_management.model.ContractTemplate;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.model.enums.RentalStatus;
import com.storagemanager.storage_management.model.enums.UnitStatus;
import com.storagemanager.storage_management.repository.ClientRepository;
import com.storagemanager.storage_management.repository.PaymentRepository;
import com.storagemanager.storage_management.repository.RentalAgreementRepository;
import com.storagemanager.storage_management.repository.ContractTemplateRepository;
import com.storagemanager.storage_management.repository.RentalDocumentRepository;
import com.storagemanager.storage_management.repository.StorageUnitRepository;
import com.storagemanager.storage_management.security.UnitScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RentalAgreementService {

    private final RentalAgreementRepository rentalAgreementRepository;
    private final StorageUnitRepository storageUnitRepository;
    private final ClientRepository clientRepository;
    private final PaymentRepository paymentRepository;
    private final RentalDocumentRepository rentalDocumentRepository;
    private final ContractTemplateRepository contractTemplateRepository;
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

        // Un contrato que ya terminó se registra igual: lo que no puede es pisarse
        // con otro de la misma unidad, y de eso se encarga requireNoOverlap. Mirar
        // el estado de hoy impediría dar de alta el histórico de una unidad que
        // ahora está alquilada, que es justo lo que se quiere poder hacer.
        boolean inForce = request.getEndDate() == null || !request.getEndDate().isBefore(LocalDate.now());
        if (inForce && unit.getStatus() == UnitStatus.MAINTENANCE) {
            throw new BadRequestException("La unidad " + unit.getUnitNumber() + " está en mantenimiento");
        }

        Client client = clientRepository.findById(request.getClientId())
                .orElseThrow(() -> new ResourceNotFoundException("Client not found with id: " + request.getClientId()));
        Client coClient = resolveCoClient(request.getCoClientId(), client);
        requireNoOverlap(unit, null, request.getStartDate(), request.getEndDate());

        String agreementNumber = "RNT-" + LocalDate.now().getYear() + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        // Facturar sólo donde hay IVA que repercutir (ver invoicingAllowed).
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
                // Con una fecha de fin ya pasada, el contrato nace terminado: es
                // histórico que se está registrando, no un alquiler que empieza.
                .status(inForce ? RentalStatus.ACTIVE : RentalStatus.TERMINATED)
                .autoRenew(request.getAutoRenew() != null ? request.getAutoRenew() : true)
                .generatesInvoices(invoicingAllowed(unit, request.getGeneratesInvoices()))
                .contractTemplate(templateOf(request.getContractTemplateId()))
                .notes(request.getNotes())
                .build();

        // Y por lo mismo, sólo ocupa la unidad el que está en vigor.
        if (inForce) {
            unit.setStatus(UnitStatus.OCCUPIED);
            storageUnitRepository.save(unit);
        }

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
        requireNoOverlap(agreement.getStorageUnit(), agreement.getId(), request.getStartDate(), request.getEndDate());
        agreement.setStartDate(request.getStartDate());
        agreement.setEndDate(request.getEndDate());
        agreement.setBillingDayOfMonth(request.getBillingDayOfMonth() != null ? request.getBillingDayOfMonth() : 1);
        agreement.setMonthlyRent(request.getMonthlyRent());
        agreement.setSecurityDeposit(request.getSecurityDeposit());
        if (request.getDepositPaid() != null) {
            agreement.setDepositPaid(request.getDepositPaid());
        }
        agreement.setContractTemplate(templateOf(request.getContractTemplateId()));
        if (request.getGeneratesInvoices() != null) {
            agreement.setGeneratesInvoices(invoicingAllowed(agreement.getStorageUnit(), request.getGeneratesInvoices()));
        }
        if (request.getAutoRenew() != null) {
            agreement.setAutoRenew(request.getAutoRenew());
        }
        agreement.setNotes(request.getNotes());

        return rentalAgreementRepository.save(agreement);
    }

    /**
     * Vuelve a poner en vigor un contrato que se dio por terminado sin estarlo
     * (una fecha de fin puesta por error, una migración que lo cerró).
     * <p>
     * La unidad no puede tener otro contrato en vigor: dos a la vez sobre el
     * mismo trastero es justo el estado que hay que deshacer, no uno más que
     * crear. Si lo hay, primero se resuelve aquél —uniéndolo a éste con
     * {@link #absorb} o terminándolo—.
     */
    @Transactional
    public RentalAgreement reactivate(Long id) {
        RentalAgreement agreement = getAgreementById(id);
        if (agreement.getStatus() == RentalStatus.ACTIVE) {
            throw new BadRequestException("El contrato " + agreement.getAgreementNumber() + " ya está en vigor");
        }

        StorageUnit unit = agreement.getStorageUnit();
        rentalAgreementRepository.findByStorageUnitIdAndStatus(unit.getId(), RentalStatus.ACTIVE)
                .ifPresent(other -> {
                    throw new BadRequestException("La unidad " + unit.getUnitNumber() + " ya tiene el contrato "
                            + other.getAgreementNumber() + " en vigor (" + other.getClient().getFullName()
                            + "). Resuelve ése antes de reactivar éste.");
                });

        // Al quitarle la fecha de fin vuelve a correr hasta hoy, así que no puede
        // haber otro contrato de la unidad por el medio (aunque esté terminado).
        requireNoOverlap(unit, agreement.getId(), agreement.getStartDate(), null);

        agreement.setStatus(RentalStatus.ACTIVE);
        // Un contrato en vigor no tiene fecha de fin: si se dejara, BillingService
        // dejaría de generarle mensualidades a partir de ella.
        agreement.setEndDate(null);
        unit.setStatus(UnitStatus.OCCUPIED);
        storageUnitRepository.save(unit);

        log.info("Contrato {} reactivado; unidad {} vuelve a ocupada",
                agreement.getAgreementNumber(), unit.getUnitNumber());
        return rentalAgreementRepository.save(agreement);
    }

    /**
     * Une dos contratos duplicados de la misma unidad: los cobros y los
     * documentos de {@code sourceId} pasan a {@code targetId}, y el duplicado
     * desaparece.
     * <p>
     * Es para lo que crea una migración mal hecha: la misma unidad con dos
     * contratos, y las mensualidades registradas en el que no era. Los cobros se
     * llevan también el titular del contrato bueno, porque un cobro lleva su
     * propio cliente y es el del contrato al que pertenece.
     * <p>
     * No es un traspaso entre inquilinos: eso se hace terminando un contrato y
     * abriendo otro, y cada uno se queda con sus meses.
     */
    @Transactional
    public RentalAgreement absorb(Long targetId, Long sourceId) {
        if (targetId.equals(sourceId)) {
            throw new BadRequestException("Un contrato no se puede unir consigo mismo");
        }
        RentalAgreement target = getAgreementById(targetId);
        RentalAgreement source = getAgreementById(sourceId);

        if (!target.getStorageUnit().getId().equals(source.getStorageUnit().getId())) {
            throw new BadRequestException("Sólo se unen contratos de la misma unidad: "
                    + target.getAgreementNumber() + " es de la " + target.getStorageUnit().getUnitNumber()
                    + " y " + source.getAgreementNumber() + " de la " + source.getStorageUnit().getUnitNumber());
        }

        List<Payment> moving = paymentRepository.findByRentalAgreementId(sourceId);

        // Un contrato no puede tener dos cobros del mismo mes (índice único
        // ux_payments_agreement_period): si los dos contratos cubren el mismo
        // periodo hay que decidir a mano cuál vale, no dejar que reviente al guardar.
        Set<YearMonth> targetPeriods = paymentRepository.findByRentalAgreementId(targetId).stream()
                .map(RentalAgreementService::periodOf)
                .collect(Collectors.toSet());
        List<String> clashes = moving.stream()
                .map(RentalAgreementService::periodOf)
                .filter(targetPeriods::contains)
                .sorted()
                .map(period -> String.format("%02d/%d", period.getMonthValue(), period.getYear()))
                .toList();
        if (!clashes.isEmpty()) {
            throw new BadRequestException("Los dos contratos tienen cobros de los mismos meses ("
                    + String.join(", ", clashes) + "). Borra o corrige esos cobros duplicados antes de unirlos.");
        }

        moving.forEach(payment -> {
            payment.setRentalAgreement(target);
            payment.setClient(target.getClient());
            payment.setStorageUnit(target.getStorageUnit());
        });
        paymentRepository.saveAll(moving);

        // Los documentos del duplicado (una copia del contrato, una foto) se van
        // con él: si se quedaran, su borrado fallaría por la clave ajena.
        List<RentalDocument> documents =
                rentalDocumentRepository.findByRentalAgreementIdOrderByDocumentUploadedAtDescDocumentIdDesc(sourceId);
        documents.forEach(link -> link.setRentalAgreement(target));
        rentalDocumentRepository.saveAll(documents);

        rentalAgreementRepository.delete(source);

        log.info("Contrato {} unido a {}: {} cobro(s) y {} documento(s) trasladados; el duplicado se ha borrado",
                source.getAgreementNumber(), target.getAgreementNumber(), moving.size(), documents.size());

        // La unidad queda libre si el que se ha borrado era el que la ocupaba;
        // reactivar el bueno la vuelve a ocupar.
        StorageUnit unit = target.getStorageUnit();
        if (source.getStatus() == RentalStatus.ACTIVE && target.getStatus() != RentalStatus.ACTIVE) {
            unit.setStatus(UnitStatus.AVAILABLE);
            storageUnitRepository.save(unit);
        }
        return target;
    }

    /** El periodo facturado de un cobro, para comparar meses entre contratos. */
    private static YearMonth periodOf(Payment payment) {
        return YearMonth.of(payment.getBillingPeriodYear(), payment.getBillingPeriodMonth());
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

    /**
     * Dos contratos de la misma unidad no pueden pisarse: uno termina el último día
     * del mes y el siguiente empieza el día 1 del siguiente. Si se solapan aunque
     * sea un día —lo típico, poner como fin el día en que entra el inquilino
     * nuevo—, ese mes se le factura a los dos, y al que se fue le aparece un cargo
     * vencido por un mes que no debe.
     *
     * @param agreementId el contrato que se está guardando, para no compararlo consigo mismo
     */
    private void requireNoOverlap(StorageUnit unit, Long agreementId, LocalDate start, LocalDate end) {
        if (start == null) return;
        for (RentalAgreement other : rentalAgreementRepository.findByStorageUnitId(unit.getId())) {
            if (other.getId().equals(agreementId) || other.getStartDate() == null) continue;
            LocalDate otherEnd = other.getEndDate();
            boolean overlaps = (end == null || !end.isBefore(other.getStartDate()))
                    && (otherEnd == null || !start.isAfter(otherEnd));
            if (overlaps) {
                throw new BadRequestException("El contrato se solapa con " + other.getAgreementNumber()
                        + " (" + other.getStartDate() + " → " + (otherEnd != null ? otherEnd : "abierto")
                        + ") en la unidad " + unit.getUnitNumber()
                        + ": un contrato tiene que terminar antes de que empiece el siguiente"
                        + " (el último día del mes, y el siguiente el día 1)");
            }
        }
    }

    @Transactional
    public RentalAgreement terminateAgreement(Long id, LocalDate terminationDate) {
        RentalAgreement agreement = getAgreementById(id);
        LocalDate end = terminationDate != null ? terminationDate : LocalDate.now();
        requireNoOverlap(agreement.getStorageUnit(), agreement.getId(), agreement.getStartDate(), end);
        agreement.setStatus(RentalStatus.TERMINATED);
        agreement.setEndDate(end);

        // Free up the storage unit if no other active agreement exists
        StorageUnit unit = agreement.getStorageUnit();
        unit.setStatus(UnitStatus.AVAILABLE);
        storageUnitRepository.save(unit);

        return rentalAgreementRepository.save(agreement);
    }

    /**
     * Si este contrato puede facturar. Sólo donde hay IVA que repercutir: el
     * alquiler de vivienda está exento (artículo 20.Uno.23º de la Ley 37/1992) y
     * de él no se emiten facturas, así que marcarlo se rechaza en vez de aceptar
     * una casilla que luego no haría nada.
     */
    /** La plantilla elegida, si se eligió alguna; nulo = la de por defecto. */
    private ContractTemplate templateOf(Long templateId) {
        if (templateId == null) return null;
        return contractTemplateRepository.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("Contract template not found with id: " + templateId));
    }

    private boolean invoicingAllowed(StorageUnit unit, Boolean requested) {
        if (!Boolean.TRUE.equals(requested)) return false;
        if (unit != null && !unit.isVatApplicable()) {
            throw new BadRequestException("El alquiler de " + unit.getName()
                    + " está exento de IVA: de este contrato no se pueden emitir facturas");
        }
        return true;
    }
}
