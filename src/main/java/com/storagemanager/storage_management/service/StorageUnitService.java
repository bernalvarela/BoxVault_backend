package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.config.VatUtils;
import com.storagemanager.storage_management.dto.StorageUnitRequest;
import com.storagemanager.storage_management.dto.UnitHistoryDTO;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.StorageGroup;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.model.UnitPriceHistory;
import com.storagemanager.storage_management.model.enums.UnitStatus;
import com.storagemanager.storage_management.repository.ExpenseRepository;
import com.storagemanager.storage_management.repository.PaymentRepository;
import com.storagemanager.storage_management.repository.StorageGroupRepository;
import com.storagemanager.storage_management.repository.StorageUnitRepository;
import com.storagemanager.storage_management.repository.UnitPriceHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storagemanager.storage_management.model.Client;
import com.storagemanager.storage_management.model.enums.RentalStatus;
import com.storagemanager.storage_management.repository.RentalAgreementRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class StorageUnitService {

    private final StorageUnitRepository storageUnitRepository;
    private final RentalAgreementRepository rentalAgreementRepository;
    private final UnitPriceHistoryRepository unitPriceHistoryRepository;
    private final PaymentRepository paymentRepository;
    private final ExpenseRepository expenseRepository;
    private final StorageGroupRepository storageGroupRepository;

    public List<StorageUnit> getAllUnits() {
        return storageUnitRepository.findAll();
    }

    public List<StorageUnit> getUnitsByGroup(Long groupId) {
        return storageUnitRepository.findByStorageGroupIdIn(List.of(groupId));
    }

    private StorageGroup resolveGroup(Long groupId) {
        if (groupId == null) {
            throw new BadRequestException("Storage group is required");
        }
        return storageGroupRepository.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("Storage group not found with id: " + groupId));
    }

    public StorageUnit getUnitById(Long id) {
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
        return storageUnitRepository.findByStatus(status);
    }


    @Transactional
    public StorageUnit createUnit(StorageUnitRequest request) {
        if (storageUnitRepository.findByUnitNumber(request.getUnitNumber()).isPresent()) {
            throw new BadRequestException("Storage unit number already exists: " + request.getUnitNumber());
        }

        StorageUnit unit = StorageUnit.builder()
                .unitNumber(request.getUnitNumber())
                .name(request.getName())
                .storageGroup(resolveGroup(request.getStorageGroupId()))
                .sizeSquareMeters(request.getSizeSquareMeters())
                .dimensions(request.getDimensions())
                .location(request.getLocation())
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
        unit.setStorageGroup(resolveGroup(request.getStorageGroupId()));
        unit.setSizeSquareMeters(request.getSizeSquareMeters());
        unit.setDimensions(request.getDimensions());
        unit.setLocation(request.getLocation());
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
        unitPriceHistoryRepository.deleteAll(
                unitPriceHistoryRepository.findByStorageUnitIdOrderByEffectiveFromAscIdAsc(id));
        storageUnitRepository.delete(unit);
    }

    /**
     * Full history of a unit: price evolution plus every rental agreement
     * (past and current), newest first for rentals, chronological for prices.
     */
    public UnitHistoryDTO getUnitHistory(Long id) {
        StorageUnit unit = getUnitById(id);

        List<UnitHistoryDTO.PriceEntry> prices = unitPriceHistoryRepository
                .findByStorageUnitIdOrderByEffectiveFromAscIdAsc(id).stream()
                .map(h -> UnitHistoryDTO.PriceEntry.builder()
                        .effectiveFrom(h.getEffectiveFrom())
                        .monthlyPrice(h.getMonthlyPrice())
                        .monthlyPriceWithoutVat(VatUtils.calculateBaseWithoutVat(h.getMonthlyPrice()))
                        .monthlyPriceVatAmount(VatUtils.calculateVatAmount(h.getMonthlyPrice()))
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
                .totalRevenueWithoutVat(VatUtils.calculateBaseWithoutVat(totalRevenue))
                .totalRevenueVatAmount(VatUtils.calculateVatAmount(totalRevenue))
                .totalExpenses(totalExpenses)
                .netResult(totalRevenue.subtract(totalExpenses))
                .priceHistory(prices)
                .rentalHistory(rentals)
                .build();
    }

    private void recordPrice(StorageUnit unit, java.math.BigDecimal price, String note) {
        unitPriceHistoryRepository.save(UnitPriceHistory.builder()
                .storageUnit(unit)
                .monthlyPrice(price)
                .effectiveFrom(LocalDate.now())
                .notes(note)
                .build());
    }
}
