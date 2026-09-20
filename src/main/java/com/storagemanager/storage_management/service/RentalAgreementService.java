package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.dto.RentalAgreementRequest;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.Client;
import com.storagemanager.storage_management.model.Payment;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.RentalDocument;
import com.storagemanager.storage_management.model.RentalParty;
import com.storagemanager.storage_management.model.ContractTemplate;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.model.enums.PartyRole;
import com.storagemanager.storage_management.model.enums.RentalStatus;
import com.storagemanager.storage_management.model.enums.UnitStatus;
import com.storagemanager.storage_management.repository.ClientRepository;
import com.storagemanager.storage_management.repository.PaymentRepository;
import com.storagemanager.storage_management.repository.RentalAgreementRepository;
import com.storagemanager.storage_management.repository.ContractTemplateRepository;
import com.storagemanager.storage_management.repository.RentalDocumentRepository;
import com.storagemanager.storage_management.repository.RentalPartyRepository;
import com.storagemanager.storage_management.repository.StorageUnitRepository;
import com.storagemanager.storage_management.security.UnitScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final RentalPartyRepository rentalParties;
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

    /**
     * Los contratos que ha firmado esa persona, sea el papel que sea.
     * <p>
     * Va por dos caminos a propósito: las columnas de siempre (titular y segundo
     * titular) y la lista de partes, que es donde están el tercer arrendatario y
     * los fiadores. Con sólo el primero, el tercero de un contrato no vería en
     * su ficha el contrato que ha firmado.
     */
    public List<RentalAgreement> getAgreementsByClient(Long clientId) {
        Map<Long, RentalAgreement> byId = new LinkedHashMap<>();
        rentalAgreementRepository.findByClientIdOrCoClientId(clientId, clientId)
                .forEach(rental -> byId.put(rental.getId(), rental));
        rentalParties.findByClientId(clientId).stream()
                .map(RentalParty::getRentalAgreement)
                .filter(java.util.Objects::nonNull)
                .forEach(rental -> byId.putIfAbsent(rental.getId(), rental));

        return unitScope.filterByUnit(List.copyOf(byId.values()), RentalAgreement::getStorageUnit);
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

        List<PersonInContract> people = peopleOf(request);
        Client client = people.get(0).client();
        requireNoOverlap(unit, null, request.getStartDate(), request.getEndDate());

        String agreementNumber = "RNT-" + LocalDate.now().getYear() + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        // Facturar sólo donde hay IVA que repercutir (ver invoicingAllowed).
        RentalAgreement agreement = RentalAgreement.builder()
                .agreementNumber(agreementNumber)
                .storageUnit(unit)
                .client(client)
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
                .communityFee(request.getCommunityFee())
                .propertyTax(request.getPropertyTax())
                .notes(request.getNotes())
                .build();

        applyPeople(agreement, people);

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

        // Aquí había quince líneas que reescribían el cliente de todos los cobros
        // cuando se corregía el titular. Existían para mantener a raya un
        // duplicado: cada cobro llevaba su propio cliente además del contrato. Ya
        // no lo lleva nadie -los cobros son del contrato y los arrendatarios son
        // los del contrato-, así que no hay nada que sincronizar.
        List<Client> before = List.copyOf(agreement.tenants());
        applyPeople(agreement, peopleOf(request));
        warnIfPeopleChanged(agreement, before);
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
        agreement.setCommunityFee(request.getCommunityFee());
        agreement.setPropertyTax(request.getPropertyTax());
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

    /**
     * Deja constancia cuando cambia quién alquila en un contrato que ya tiene
     * cobros registrados.
     * <p>
     * Un contrato no cambia de arrendatarios: si entra o sale alguien, lo que
     * procede es cerrarlo y firmar uno nuevo, porque lo cobrado hasta hoy lo
     * pagaron los de antes. Pero corregir una ficha mal capturada el primer día
     * es legítimo y no se puede distinguir desde aquí, así que esto no lo
     * impide: lo registra. Quien avisa de verdad, antes de guardar, es la
     * pantalla.
     */
    private void warnIfPeopleChanged(RentalAgreement agreement, List<Client> before) {
        List<Long> antes = before.stream().map(Client::getId).sorted().toList();
        List<Long> ahora = agreement.tenants().stream().map(Client::getId).sorted().toList();
        if (antes.equals(ahora)) return;

        long charges = paymentRepository.findByRentalAgreementId(agreement.getId()).size();
        if (charges == 0) return;
        log.warn("Contrato {}: cambian los arrendatarios teniendo {} cobro(s) registrado(s). "
                + "Lo cobrado hasta hoy lo pagaron los de antes; si de verdad ha entrado o salido "
                + "alguien, lo correcto es cerrar este contrato y firmar otro.",
                agreement.getAgreementNumber(), charges);
    }

    /** Una persona del contrato ya resuelta: su ficha y su papel. */
    private record PersonInContract(Client client, PartyRole role) {}

    /**
     * Quién firma el contrato, leído de la petición.
     * <p>
     * Acepta las dos formas: la lista de partes, que es la de ahora, y los tres
     * campos de siempre (clientId, coClientId, guarantorId), que es lo que manda
     * cualquier cliente que todavía no se haya enterado del cambio. La lista
     * tiene preferencia cuando viene.
     */
    private List<PersonInContract> peopleOf(RentalAgreementRequest request) {
        List<PersonInContract> people = new ArrayList<>();

        if (request.getParties() != null && !request.getParties().isEmpty()) {
            for (RentalAgreementRequest.Party party : request.getParties()) {
                if (party.getClientId() == null) continue;
                PartyRole role = party.getRole() == null ? PartyRole.ARRENDATARIO : party.getRole();
                Client person = clientRepository.findById(party.getClientId())
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Client not found with id: " + party.getClientId()));
                // La misma persona no firma dos veces con el mismo papel. Sí puede
                // firmar como arrendataria y avalar: raro, pero no imposible, y no
                // es la aplicación quien tiene que decir que no.
                boolean repeated = people.stream()
                        .anyMatch(p -> p.role() == role && p.client().getId().equals(person.getId()));
                if (repeated) {
                    throw new BadRequestException(person.getFullName()
                            + " ya está en el contrato como " + role.name().toLowerCase());
                }
                people.add(new PersonInContract(person, role));
            }
            if (people.stream().noneMatch(p -> p.role() == PartyRole.ARRENDATARIO)) {
                throw new BadRequestException("El contrato necesita al menos un arrendatario");
            }
            return people;
        }

        Client client = clientRepository.findById(request.getClientId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Client not found with id: " + request.getClientId()));
        people.add(new PersonInContract(client, PartyRole.ARRENDATARIO));
        if (request.getCoClientId() != null) {
            if (request.getCoClientId().equals(client.getId())) {
                throw new BadRequestException("The second tenant must be a different client");
            }
            people.add(new PersonInContract(clientRepository.findById(request.getCoClientId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Client not found with id: " + request.getCoClientId())),
                    PartyRole.ARRENDATARIO));
        }
        Client guarantor = clientOrNull(request.getGuarantorId());
        if (guarantor != null) people.add(new PersonInContract(guarantor, PartyRole.FIADOR));
        return people;
    }

    /**
     * Deja el contrato con exactamente esas personas, y pone al día el reflejo:
     * client es el primer arrendatario, coClient el segundo y guarantor el
     * primer fiador.
     * <p>
     * Ese reflejo es lo que leen los cobros, las facturas, el modelo 184, el
     * IRPF y el ámbito de acceso por unidad. Mientras se mantenga aquí, en un
     * solo sitio y en cada guardado, todo aquello sigue funcionando sin saber
     * que esto existe.
     */
    private void applyPeople(RentalAgreement agreement, List<PersonInContract> people) {
        // Se vacía la lista que ya tiene en vez de cambiarla por otra: es la
        // colección que vigila Hibernate, y sustituirla deja huérfanas las filas.
        agreement.getParties().clear();
        int position = 0;
        for (PersonInContract person : people) {
            agreement.getParties().add(RentalParty.builder()
                    .rentalAgreement(agreement)
                    .client(person.client())
                    .role(person.role())
                    .position(position++)
                    .build());
        }

        List<Client> tenants = people.stream()
                .filter(p -> p.role() == PartyRole.ARRENDATARIO)
                .map(PersonInContract::client)
                .toList();
        List<Client> guarantors = people.stream()
                .filter(p -> p.role() == PartyRole.FIADOR)
                .map(PersonInContract::client)
                .toList();

        agreement.setClient(tenants.get(0));
        agreement.setCoClient(tenants.size() > 1 ? tenants.get(1) : null);
        agreement.setGuarantor(guarantors.isEmpty() ? null : guarantors.get(0));
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
    /** Una ficha de cliente por su id, o nulo si no se pasó ninguno. */
    private Client clientOrNull(Long clientId) {
        if (clientId == null) return null;
        return clientRepository.findById(clientId)
                .orElseThrow(() -> new ResourceNotFoundException("Client not found with id: " + clientId));
    }

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
