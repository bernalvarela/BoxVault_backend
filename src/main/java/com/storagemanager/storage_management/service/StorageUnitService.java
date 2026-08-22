package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.dto.StorageUnitRequest;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.model.enums.UnitStatus;
import com.storagemanager.storage_management.model.enums.UnitType;
import com.storagemanager.storage_management.repository.StorageUnitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class StorageUnitService {

    private final StorageUnitRepository storageUnitRepository;

    public List<StorageUnit> getAllUnits() {
        return storageUnitRepository.findAll();
    }

    public StorageUnit getUnitById(Long id) {
        return storageUnitRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Storage unit not found with id: " + id));
    }

    public List<StorageUnit> getUnitsByStatus(UnitStatus status) {
        return storageUnitRepository.findByStatus(status);
    }

    public List<StorageUnit> getUnitsByType(UnitType type) {
        return storageUnitRepository.findByType(type);
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
                .type(request.getType())
                .location(request.getLocation())
                .baseMonthlyRate(request.getBaseMonthlyRate())
                .status(request.getStatus() != null ? request.getStatus() : UnitStatus.AVAILABLE)
                .features(request.getFeatures())
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
        unit.setType(request.getType());
        unit.setLocation(request.getLocation());
        unit.setBaseMonthlyRate(request.getBaseMonthlyRate());
        if (request.getStatus() != null) {
            unit.setStatus(request.getStatus());
        }
        unit.setFeatures(request.getFeatures());
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
