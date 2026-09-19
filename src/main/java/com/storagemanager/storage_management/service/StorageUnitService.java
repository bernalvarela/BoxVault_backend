package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.config.VatUtils;
import com.storagemanager.storage_management.dto.StorageUnitRequest;
import com.storagemanager.storage_management.dto.UnitHistoryDTO;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.Client;
import com.storagemanager.storage_management.model.ContractTemplate;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.model.UnitPriceHistory;
import com.storagemanager.storage_management.model.enums.RentalStatus;
import com.storagemanager.storage_management.model.enums.UnitKind;
import com.storagemanager.storage_management.model.enums.UnitStatus;
import com.storagemanager.storage_management.repository.ExpenseRepository;
import com.storagemanager.storage_management.repository.OwnershipRepository;
import com.storagemanager.storage_management.repository.PaymentRepository;
import com.storagemanager.storage_management.repository.RentalAgreementRepository;
import com.storagemanager.storage_management.repository.ContractTemplateRepository;
import com.storagemanager.storage_management.repository.StorageUnitRepository;
import com.storagemanager.storage_management.repository.UnitPriceHistoryRepository;
import com.storagemanager.storage_management.security.UnitScope;
import org.springframework.security.access.AccessDeniedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class StorageUnitService {

    private final StorageUnitRepository storageUnitRepository;
    private final ContractTemplateRepository contractTemplateRepository;
    private final RentalAgreementRepository rentalAgreementRepository;
    private final UnitPriceHistoryRepository unitPriceHistoryRepository;
    private final PaymentRepository paymentRepository;
    private final ExpenseRepository expenseRepository;
    private final OwnershipRepository ownershipRepository;
    private final UnitScope unitScope;

    /**
     * Las unidades que ve quien pregunta: las suyas y, sólo para que el árbol se
     * entienda, los locales que las contienen (ver {@link UnitScope}).
     */
    public List<StorageUnit> getAllUnits() {
        return unitScope.visibleUnits(storageUnitRepository.findAll());
    }

    /** Units directly inside a local. */
    public List<StorageUnit> getChildren(Long parentId) {
        // Sin exigir que el local esté en el ámbito: puede ser uno de contexto
        // —se ve porque dentro hay unidades del usuario—, y lo que se devuelve
        // son sólo las unidades que le tocan.
        requireExisting(parentId);
        return unitScope.visibleUnits(storageUnitRepository.findByParentId(parentId));
    }

    /**
     * The parent of a unit being created / updated: must exist, must not be the unit
     * itself and must not sit (directly or through its own parents) inside the unit.
     */
    private StorageUnit resolveParent(Long parentId, Long selfId) {
        if (parentId == null) return null;
        if (selfId != null && parentId.equals(selfId)) {
            throw new BadRequestException("A unit cannot be placed inside itself");
        }
        StorageUnit parent = getUnitById(parentId);
        int guard = 0;
        for (StorageUnit p = parent; p != null && guard++ < 32; p = p.getParent()) {
            if (selfId != null && selfId.equals(p.getId())) {
                throw new BadRequestException("A unit cannot be placed inside one of the units it contains");
            }
        }
        return parent;
    }

    /**
     * Una unidad concreta, comprobando que sea del usuario. Todo lo que pase por
     * aquí —abrirla, editarla, colgarle algo— queda cubierto; para los usos
     * internos que sólo necesitan saber que existe está {@link #requireExisting}.
     */
    public StorageUnit getUnitById(Long id) {
        StorageUnit unit = requireExisting(id);
        unitScope.requireAccessible(unit);
        return unit;
    }

    /** La unidad, sin mirar el ámbito: para comprobaciones internas. */
    private StorageUnit requireExisting(Long id) {
        return storageUnitRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Storage unit not found with id: " + id));
    }

    public Optional<Client> getClientByUnitId(Long id) {
        // Verify the unit exists first
        getUnitById(id);
        return rentalAgreementRepository
                .findByStorageUnitIdAndStatus(id, RentalStatus.ACTIVE)
                .map(ra -> ra.getClient());
    }

    public List<StorageUnit> getUnitsByStatus(UnitStatus status) {
        return unitScope.visibleUnits(storageUnitRepository.findByStatus(status));
    }

    /** Units filtered by kind and/or status; both null returns everything. */
    public List<StorageUnit> getUnits(UnitKind kind, UnitStatus status) {
        return unitScope.visibleUnits(unitsMatching(kind, status));
    }

    private List<StorageUnit> unitsMatching(UnitKind kind, UnitStatus status) {
        if (kind != null && status != null) return storageUnitRepository.findByKindAndStatus(kind, status);
        if (kind != null) return storageUnitRepository.findByKind(kind);
        if (status != null) return storageUnitRepository.findByStatus(status);
        return storageUnitRepository.findAll();
    }

    @Transactional
    public StorageUnit createUnit(StorageUnitRequest request) {
        if (storageUnitRepository.findByUnitNumber(request.getUnitNumber()).isPresent()) {
            throw new BadRequestException("Storage unit number already exists: " + request.getUnitNumber());
        }
        // Una unidad sin padre es un inmueble nuevo, que no cuelga del ámbito de
        // nadie: crearlo es cosa de quien ve todas las unidades. Con padre, basta
        // con que el padre sea suyo, y de eso se encarga resolveParent.
        if (request.getParentId() == null && !unitScope.isUnrestricted()) {
            throw new AccessDeniedException("Sólo quien ve todas las unidades puede crear un inmueble nuevo");
        }

        StorageUnit unit = StorageUnit.builder()
                .unitNumber(request.getUnitNumber())
                .name(request.getName())
                .kind(request.getKind() != null ? request.getKind() : UnitKind.STORAGE_UNIT)
                .parent(resolveParent(request.getParentId(), null))
                .sizeSquareMeters(request.getSizeSquareMeters())
                .dimensions(request.getDimensions())
                .location(request.getLocation())
                .cadastralReference(trimToNull(request.getCadastralReference()))
                .inventory(trimToNull(request.getInventory()))
                .contractTemplate(templateOf(request.getContractTemplateId()))
                .baseMonthlyRate(request.getBaseMonthlyRate())
                .status(request.getStatus() != null ? request.getStatus() : UnitStatus.AVAILABLE)
                .description(request.getDescription())
                .build();

        StorageUnit saved = storageUnitRepository.save(unit);
        recordPrice(saved, saved.getBaseMonthlyRate(), "Precio inicial");
        return saved;
    }

    @Transactional
    public StorageUnit updateUnit(Long id, StorageUnitRequest request) {
        StorageUnit unit = getUnitById(id);

        if (!unit.getUnitNumber().equalsIgnoreCase(request.getUnitNumber()) &&
                storageUnitRepository.findByUnitNumber(request.getUnitNumber()).isPresent()) {
            throw new BadRequestException("Storage unit number already exists: " + request.getUnitNumber());
        }

        boolean priceChanged = request.getBaseMonthlyRate() != null
                && unit.getBaseMonthlyRate().compareTo(request.getBaseMonthlyRate()) != 0;

        unit.setUnitNumber(request.getUnitNumber());
        unit.setName(request.getName());
        if (request.getKind() != null) {
            unit.setKind(request.getKind());
        }
        unit.setParent(resolveParent(request.getParentId(), id));
        unit.setSizeSquareMeters(request.getSizeSquareMeters());
        unit.setDimensions(request.getDimensions());
        unit.setLocation(request.getLocation());
        unit.setCadastralReference(trimToNull(request.getCadastralReference()));
        unit.setInventory(trimToNull(request.getInventory()));
        unit.setContractTemplate(templateOf(request.getContractTemplateId()));
        unit.setBaseMonthlyRate(request.getBaseMonthlyRate());
        if (priceChanged) {
            recordPrice(unit, request.getBaseMonthlyRate(), "Cambio de precio");
        }
        if (request.getStatus() != null) {
            unit.setStatus(request.getStatus());
        }
        unit.setDescription(request.getDescription());

        return storageUnitRepository.save(unit);
    }

    @Transactional
    public void updateStatus(Long id, UnitStatus status) {
        StorageUnit unit = getUnitById(id);
        unit.setStatus(status);
        storageUnitRepository.save(unit);
    }

    @Transactional
    public void deleteUnit(Long id) {
        StorageUnit unit = getUnitById(id);
        if (unit.getStatus() == UnitStatus.OCCUPIED) {
            throw new BadRequestException("Cannot delete storage unit while it is occupied by an active rental");
        }
        long children = storageUnitRepository.countByParentId(id);
        if (children > 0) {
            throw new BadRequestException("Cannot delete '" + unit.getName() + "' while it still contains "
                    + children + " unit(s). Move them elsewhere first.");
        }
        long expenses = expenseRepository.countByStorageUnitId(id);
        if (expenses > 0) {
            throw new BadRequestException("Cannot delete '" + unit.getName() + "' while " + expenses
                    + " expense(s) are attributed to it.");
        }
        unitPriceHistoryRepository.deleteAll(
                unitPriceHistoryRepository.findByStorageUnitIdOrderByEffectiveFromAscIdAsc(id));
        ownershipRepository.deleteByStorageUnitId(id);
        storageUnitRepository.delete(unit);
    }

    /**
     * Full history of a unit: price evolution plus every rental agreement
     * (past and current), newest first for rentals, chronological for prices.
     */
    public UnitHistoryDTO getUnitHistory(Long id) {
        StorageUnit unit = getUnitById(id);
        boolean vat = unit.isVatApplicable();

        List<UnitHistoryDTO.PriceEntry> prices = unitPriceHistoryRepository
                .findByStorageUnitIdOrderByEffectiveFromAscIdAsc(id).stream()
                .map(h -> UnitHistoryDTO.PriceEntry.builder()
                        .effectiveFrom(h.getEffectiveFrom())
                        .monthlyPrice(h.getMonthlyPrice())
                        .monthlyPriceWithoutVat(VatUtils.calculateBaseWithoutVat(h.getMonthlyPrice(), vat))
                        .monthlyPriceVatAmount(VatUtils.calculateVatAmount(h.getMonthlyPrice(), vat))
                        .notes(h.getNotes())
                        .build())
                .toList();

        List<UnitHistoryDTO.RentalEntry> rentals = rentalAgreementRepository
                .findByStorageUnitId(id).stream()
                .sorted((a, b) -> b.getStartDate().compareTo(a.getStartDate()))
                .map(r -> UnitHistoryDTO.RentalEntry.builder()
                        .rentalId(r.getId())
                        .agreementNumber(r.getAgreementNumber())
                        .clientName(r.getClient().getFullName())
                        .clientDocumentId(r.getClient().getDocumentId())
                        .coClientName(r.getCoClient() != null ? r.getCoClient().getFullName() : null)
                        .coClientDocumentId(r.getCoClient() != null ? r.getCoClient().getDocumentId() : null)
                        .startDate(r.getStartDate())
                        .endDate(r.getEndDate())
                        .monthlyRent(r.getMonthlyRent())
                        .status(r.getStatus())
                        .build())
                .toList();

        java.math.BigDecimal totalRevenue = paymentRepository.sumPaidRevenueForUnit(id);
        if (totalRevenue == null) totalRevenue = java.math.BigDecimal.ZERO;

        java.math.BigDecimal totalExpenses = expenseRepository.sumExpensesForUnit(id);
        if (totalExpenses == null) totalExpenses = java.math.BigDecimal.ZERO;

        return UnitHistoryDTO.builder()
                .unitId(unit.getId())
                .unitNumber(unit.getUnitNumber())
                .unitName(unit.getName())
                .currentMonthlyPrice(unit.getBaseMonthlyRate())
                .totalRevenue(totalRevenue)
                .totalRevenueWithoutVat(VatUtils.calculateBaseWithoutVat(totalRevenue, vat))
                .totalRevenueVatAmount(VatUtils.calculateVatAmount(totalRevenue, vat))
                .totalExpenses(totalExpenses)
                .netResult(totalRevenue.subtract(totalExpenses))
                .priceHistory(prices)
                .rentalHistory(rentals)
                .build();
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void recordPrice(StorageUnit unit, java.math.BigDecimal price, String note) {
        unitPriceHistoryRepository.save(UnitPriceHistory.builder()
                .storageUnit(unit)
                .monthlyPrice(price)
                .effectiveFrom(LocalDate.now())
                .notes(note)
                .build());
    }

    /** La plantilla elegida para esta unidad, si se eligió alguna. */
    private ContractTemplate templateOf(Long templateId) {
        if (templateId == null) return null;
        return contractTemplateRepository.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("Contract template not found with id: " + templateId));
    }
}
