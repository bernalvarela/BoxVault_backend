package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.dto.CommunityDTOs.*;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.*;
import com.storagemanager.storage_management.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * La comunidad de propietarios: su presupuesto, su libro y su estado de cuentas.
 * <p>
 * Lo que se anota aquí no es de nadie más. Un gasto de la comunidad —el ascensor,
 * el seguro del portal— <strong>no</strong> es gasto deducible de ningún
 * propietario: lo deducible para él es su cuota, ni un euro más. Por eso este
 * libro no toca la tabla de gastos y no entra en el 184 ni en el IRPF.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommunityService {

    private static final BigDecimal MONTHS = new BigDecimal("12");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final OwnersCommunityRepository communities;
    private final CommunityBudgetRepository budgets;
    private final CommunityEntryRepository entries;
    private final CommunityEntryDocumentRepository entryDocuments;
    private final BuildingRepository buildings;
    private final StorageUnitRepository units;
    private final BuildingService buildingService;

    // ------------------------------------------------------------------
    // La comunidad
    // ------------------------------------------------------------------

    public List<CommunityDTO> list() {
        return communities.findAllByOrderByNameAsc().stream()
                .filter(community -> visible(community.getId()))
                .map(community -> CommunityDTO.of(community, buildingNamesOf(community.getId())))
                .toList();
    }

    @Transactional
    public CommunityDTO create(CommunityRequest request) {
        OwnersCommunity community = communities.save(OwnersCommunity.builder()
                .name(request.getName().trim())
                .taxId(trimToNull(request.getTaxId()))
                .iban(trimToNull(request.getIban()))
                .notes(trimToNull(request.getNotes()))
                .build());
        log.info("Creada la comunidad de propietarios {} ({})", community.getName(), community.getId());
        return CommunityDTO.of(community, List.of());
    }

    @Transactional
    public CommunityDTO update(Long id, CommunityRequest request) {
        OwnersCommunity community = require(id);
        community.setName(request.getName().trim());
        community.setTaxId(trimToNull(request.getTaxId()));
        community.setIban(trimToNull(request.getIban()));
        community.setNotes(trimToNull(request.getNotes()));
        communities.save(community);
        return CommunityDTO.of(community, buildingNamesOf(id));
    }

    /** No se borra una comunidad con edificios detrás: primero se los quitas. */
    @Transactional
    public void delete(Long id) {
        OwnersCommunity community = require(id);
        List<String> names = buildingNamesOf(id);
        if (!names.isEmpty()) {
            throw new BadRequestException("La comunidad " + community.getName() + " administra "
                    + String.join(", ", names) + ". Quítasela a esos edificios antes de borrarla.");
        }
        communities.delete(community);
    }

    // ------------------------------------------------------------------
    // El presupuesto
    // ------------------------------------------------------------------

    public List<BudgetDTO> budgets(Long communityId) {
        requireVisible(communityId);
        return budgets.findByCommunityIdOrderByYearDesc(communityId).stream()
                .map(BudgetDTO::of)
                .toList();
    }

    @Transactional
    public BudgetDTO saveBudget(Long communityId, BudgetRequest request) {
        OwnersCommunity community = requireVisible(communityId);
        if (request.getAnnualAmount().signum() < 0) {
            throw new BadRequestException("Un presupuesto no puede ser negativo");
        }
        // Uno por ejercicio: aprobar el de 2027 dos veces es corregirlo, no
        // tener dos presupuestos.
        CommunityBudget budget = budgets.findByCommunityIdAndYear(communityId, request.getYear())
                .orElseGet(() -> CommunityBudget.builder().community(community).year(request.getYear()).build());
        budget.setAnnualAmount(request.getAnnualAmount());
        budget.setNotes(trimToNull(request.getNotes()));
        return BudgetDTO.of(budgets.save(budget));
    }

    @Transactional
    public void deleteBudget(Long communityId, Long budgetId) {
        requireVisible(communityId);
        CommunityBudget budget = budgets.findById(budgetId)
                .orElseThrow(() -> new ResourceNotFoundException("Budget not found with id: " + budgetId));
        if (!budget.getCommunity().getId().equals(communityId)) {
            throw new BadRequestException("Ese presupuesto no es de esta comunidad");
        }
        budgets.delete(budget);
    }

    // ------------------------------------------------------------------
    // El libro
    // ------------------------------------------------------------------

    public List<EntryDTO> book(Long communityId, Integer year) {
        requireVisible(communityId);
        return entries.findByCommunityIdOrderByEntryDateDescIdDesc(communityId).stream()
                .filter(entry -> year == null || entry.getEntryDate().getYear() == year)
                .map(entry -> EntryDTO.of(entry, entryDocuments.findByEntryId(entry.getId()).size()))
                .toList();
    }

    @Transactional
    public EntryDTO addEntry(Long communityId, EntryRequest request) {
        OwnersCommunity community = requireVisible(communityId);
        return EntryDTO.of(entries.save(build(community, new CommunityEntry(), request)), 0);
    }

    @Transactional
    public EntryDTO updateEntry(Long communityId, Long entryId, EntryRequest request) {
        OwnersCommunity community = requireVisible(communityId);
        CommunityEntry entry = requireEntry(communityId, entryId);
        return EntryDTO.of(entries.save(build(community, entry, request)),
                entryDocuments.findByEntryId(entryId).size());
    }

    @Transactional
    public void deleteEntry(Long communityId, Long entryId) {
        requireVisible(communityId);
        entries.delete(requireEntry(communityId, entryId));
    }

    private CommunityEntry build(OwnersCommunity community, CommunityEntry entry, EntryRequest request) {
        if (request.getAmount().signum() <= 0) {
            // El signo lo pone el tipo del apunte, no quien lo teclea: así un
            // gasto no puede colarse como ingreso por un menos mal puesto.
            throw new BadRequestException("El importe se escribe en positivo; que sume o reste lo dice el tipo");
        }
        StorageUnit unit = null;
        if (request.getStorageUnitId() != null) {
            unit = units.findById(request.getStorageUnitId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Storage unit not found with id: " + request.getStorageUnitId()));
        }
        entry.setCommunity(community);
        entry.setEntryDate(request.getEntryDate());
        entry.setType(request.getType());
        entry.setConcept(request.getConcept().trim());
        entry.setAmount(request.getAmount());
        entry.setStorageUnit(unit);
        entry.setSupplier(trimToNull(request.getSupplier()));
        entry.setNotes(trimToNull(request.getNotes()));
        return entry;
    }

    // ------------------------------------------------------------------
    // El estado de cuentas
    // ------------------------------------------------------------------

    /**
     * Qué ha entrado, qué ha salido y qué debe cada unidad en un ejercicio.
     * <p>
     * Lo esperado de una unidad es su cuota por los meses transcurridos del año
     * —los doce si el ejercicio ya pasó—, no un calendario de recibos: aquí no
     * se emiten, se anotan. Sirve para ver de un vistazo quién va atrasado, que
     * es para lo que se mira esta pantalla.
     */
    public StatementDTO statement(Long communityId, int year) {
        OwnersCommunity community = requireVisible(communityId);
        BigDecimal budget = budgets.findByCommunityIdAndYear(communityId, year)
                .map(CommunityBudget::getAnnualAmount)
                .orElse(BigDecimal.ZERO);

        List<CommunityEntry> ofYear = entries.findByCommunityIdOrderByEntryDateDescIdDesc(communityId).stream()
                .filter(entry -> entry.getEntryDate().getYear() == year)
                .toList();
        BigDecimal income = sum(ofYear.stream().filter(e -> e.getType().isIncome()).toList());
        BigDecimal expenses = sum(ofYear.stream().filter(e -> !e.getType().isIncome()).toList());

        List<StorageUnit> participating = unitsOf(communityId);
        BigDecimal coefficientTotal = participating.stream()
                .map(StorageUnit::getParticipationCoefficient)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        int monthsElapsed = monthsElapsed(year);
        List<UnitStatementDTO> lines = participating.stream()
                .sorted(Comparator.comparing(StorageUnit::getUnitNumber,
                        String.CASE_INSENSITIVE_ORDER))
                .map(unit -> lineOf(unit, budget, monthsElapsed, ofYear))
                .toList();

        return StatementDTO.builder()
                .communityId(communityId)
                .communityName(community.getName())
                .year(year)
                .budget(budget)
                .income(income)
                .expenses(expenses)
                .balance(income.subtract(expenses))
                .coefficientTotal(coefficientTotal)
                .units(lines)
                .build();
    }

    private UnitStatementDTO lineOf(StorageUnit unit, BigDecimal budget, int monthsElapsed,
                                    List<CommunityEntry> ofYear) {
        BigDecimal coefficient = unit.getParticipationCoefficient();
        BigDecimal monthly = coefficient == null || budget.signum() == 0
                ? BigDecimal.ZERO
                : budget.multiply(coefficient).divide(HUNDRED, 10, RoundingMode.HALF_UP)
                        .divide(MONTHS, 2, RoundingMode.HALF_UP);
        BigDecimal expected = monthly.multiply(BigDecimal.valueOf(monthsElapsed));
        BigDecimal paid = sum(ofYear.stream()
                .filter(entry -> entry.getStorageUnit() != null
                        && entry.getStorageUnit().getId().equals(unit.getId())
                        && entry.getType().isIncome())
                .toList());

        return UnitStatementDTO.builder()
                .unitId(unit.getId())
                .unitNumber(unit.getUnitNumber())
                .unitName(unit.getName())
                .buildingName(unit.building() == null ? null : unit.building().getName())
                .coefficient(coefficient)
                .monthlyQuota(monthly)
                .expected(expected)
                .paid(paid)
                .outstanding(expected.subtract(paid))
                .build();
    }

    /** Las unidades que participan: las raíz de los edificios de esta comunidad. */
    public List<StorageUnit> unitsOf(Long communityId) {
        Set<Long> buildingIds = buildings.findAll().stream()
                .filter(b -> b.getCommunity() != null && b.getCommunity().getId().equals(communityId))
                .map(Building::getId)
                .collect(java.util.stream.Collectors.toSet());
        return units.findAll().stream()
                .filter(unit -> unit.getBuilding() != null && buildingIds.contains(unit.getBuilding().getId()))
                .toList();
    }

    private int monthsElapsed(int year) {
        LocalDate today = LocalDate.now();
        if (year < today.getYear()) return 12;
        if (year > today.getYear()) return 0;
        return today.getMonthValue();
    }

    private BigDecimal sum(List<CommunityEntry> some) {
        return some.stream().map(CommunityEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ------------------------------------------------------------------

    private List<String> buildingNamesOf(Long communityId) {
        return buildings.findAllByOrderByNameAsc().stream()
                .filter(b -> b.getCommunity() != null && b.getCommunity().getId().equals(communityId))
                .map(Building::getName)
                .toList();
    }

    /** Se ve una comunidad si se alcanza alguno de sus edificios. */
    private boolean visible(Long communityId) {
        Set<Long> visibleBuildings = buildingService.visibleBuildingIds();
        if (visibleBuildings == null) return true;
        return buildings.findAll().stream()
                .filter(b -> b.getCommunity() != null && b.getCommunity().getId().equals(communityId))
                .anyMatch(b -> visibleBuildings.contains(b.getId()));
    }

    private OwnersCommunity requireVisible(Long communityId) {
        OwnersCommunity community = require(communityId);
        if (!visible(communityId)) {
            throw new AccessDeniedException("Esa comunidad no está en tu ámbito");
        }
        return community;
    }

    private OwnersCommunity require(Long id) {
        return communities.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Community not found with id: " + id));
    }

    private CommunityEntry requireEntry(Long communityId, Long entryId) {
        CommunityEntry entry = entries.findById(entryId)
                .orElseThrow(() -> new ResourceNotFoundException("Entry not found with id: " + entryId));
        if (!entry.getCommunity().getId().equals(communityId)) {
            throw new BadRequestException("Ese apunte no es de esta comunidad");
        }
        return entry;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
