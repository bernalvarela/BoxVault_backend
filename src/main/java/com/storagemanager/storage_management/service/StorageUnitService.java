package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.dto.StorageUnitRequest;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.model.enums.UnitStatus;
import com.storagemanager.storage_management.repository.StorageUnitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storagemanager.storage_management.model.Client;
import com.storagemanager.storage_management.model.enums.RentalStatus;
import com.storagemanager.storage_management.repository.RentalAgreementRepository;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class StorageUnitService {

    private final StorageUnitRepository storageUnitRepository;
    private final RentalAgreementRepository rentalAgreementRepository;

    public List<StorageUnit> getAllUnits() {
        return storageUnitRepository.findAll();
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
                .sizeSquareMeters(request.getSizeSquareMeters())
                .dimensions(request.getDimensions())
                .location(request.getLocation())
                .baseMonthlyRate(request.getBaseMonthlyRate())
                .status(request.getStatus() != null ? request.getStatus() : UnitStatus.AVAILABLE)
                .description(request.getDescription())
                .build();

        return storageUnitRepository.save(unit);
    }

    @Transactional
    public StorageUnit updateUnit(Long id, StorageUnitRequest request) {
        StorageUnit unit = getUnitById(id);

        if (!unit.getUnitNumber().equalsIgnoreCase(request.getUnitNumber()) &&
                storageUnitRepository.findByUnitNumber(request.getUnitNumber()).isPresent()) {
            throw new BadRequestException("Storage unit number already exists: " + request.getUnitNumber());
        }

        unit.setUnitNumber(request.getUnitNumber());
        unit.setName(request.getName());
        unit.setSizeSquareMeters(request.getSizeSquareMeters());
        unit.setDimensions(request.getDimensions());
        unit.setLocation(request.getLocation());
        unit.setBaseMonthlyRate(request.getBaseMonthlyRate());
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
        storageUnitRepository.delete(unit);
    }
}
