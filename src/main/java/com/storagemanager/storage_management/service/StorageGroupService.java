package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.dto.StorageGroupDTO;
import com.storagemanager.storage_management.dto.StorageGroupRequest;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.StorageGroup;
import com.storagemanager.storage_management.model.enums.UnitStatus;
import com.storagemanager.storage_management.repository.ExpenseRepository;
import com.storagemanager.storage_management.repository.StorageGroupRepository;
import com.storagemanager.storage_management.repository.StorageUnitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class StorageGroupService {

    private final StorageGroupRepository storageGroupRepository;
    private final StorageUnitRepository storageUnitRepository;
    private final ExpenseRepository expenseRepository;

    public List<StorageGroupDTO> getAllGroups() {
        return storageGroupRepository.findAllByOrderByNameAsc().stream()
                .map(this::toDto)
                .toList();
    }

    public StorageGroup getGroupById(Long id) {
        return storageGroupRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Storage group not found with id: " + id));
    }

    public StorageGroupDTO getGroupDtoById(Long id) {
        return toDto(getGroupById(id));
    }

    @Transactional
    public StorageGroupDTO createGroup(StorageGroupRequest request) {
        String name = request.getName().trim();
        if (storageGroupRepository.findByNameIgnoreCase(name).isPresent()) {
            throw new BadRequestException("Storage group name already exists: " + name);
        }
        StorageGroup group = StorageGroup.builder()
                .name(name)
                .description(trimToNull(request.getDescription()))
                .build();
        return toDto(storageGroupRepository.save(group));
    }

    @Transactional
    public StorageGroupDTO updateGroup(Long id, StorageGroupRequest request) {
        StorageGroup group = getGroupById(id);
        String name = request.getName().trim();
        storageGroupRepository.findByNameIgnoreCase(name)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new BadRequestException("Storage group name already exists: " + name);
                });
        group.setName(name);
        group.setDescription(trimToNull(request.getDescription()));
        return toDto(storageGroupRepository.save(group));
    }

    @Transactional
    public void deleteGroup(Long id) {
        StorageGroup group = getGroupById(id);
        long units = storageUnitRepository.countByStorageGroupId(id);
        if (units > 0) {
            throw new BadRequestException("Cannot delete storage group '" + group.getName()
                    + "' while it still contains " + units + " storage unit(s). Move them to another group first.");
        }
        long expenses = expenseRepository.countByStorageGroupId(id);
        if (expenses > 0) {
            throw new BadRequestException("Cannot delete storage group '" + group.getName()
                    + "' while " + expenses + " expense(s) are attributed to it.");
        }
        storageGroupRepository.delete(group);
    }

    private StorageGroupDTO toDto(StorageGroup group) {
        return StorageGroupDTO.builder()
                .id(group.getId())
                .name(group.getName())
                .description(group.getDescription())
                .unitCount(storageUnitRepository.countByStorageGroupId(group.getId()))
                .occupiedUnits(storageUnitRepository.countByStorageGroupIdAndStatus(group.getId(), UnitStatus.OCCUPIED))
                .build();
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
