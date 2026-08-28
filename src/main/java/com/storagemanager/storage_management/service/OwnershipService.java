package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.dto.OwnershipDTO;
import com.storagemanager.storage_management.dto.OwnershipRequest;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.Owner;
import com.storagemanager.storage_management.model.Ownership;
import com.storagemanager.storage_management.model.StorageGroup;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.repository.OwnerRepository;
import com.storagemanager.storage_management.repository.OwnershipRepository;
import com.storagemanager.storage_management.repository.StorageGroupRepository;
import com.storagemanager.storage_management.repository.StorageUnitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

/**
 * Shares of owners in groups and units. See {@link Ownership} for the
 * resolution rule (unit-level shares override the group-level ones).
 */
@Service
@RequiredArgsConstructor
public class OwnershipService {

    private final OwnershipRepository ownershipRepository;
    private final OwnerRepository ownerRepository;
    private final StorageUnitRepository storageUnitRepository;
    private final StorageGroupRepository storageGroupRepository;

    private static final Comparator<Ownership> BY_TARGET_THEN_SHARE = Comparator
            .comparing((Ownership o) -> o.getStorageGroup() != null ? o.getStorageGroup().getName() : o.getStorageUnit().getStorageGroup() != null ? o.getStorageUnit().getStorageGroup().getName() : "", String.CASE_INSENSITIVE_ORDER)
            .thenComparing(o -> o.getStorageUnit() != null ? 1 : 0)
            .thenComparing(o -> o.getStorageUnit() != null ? o.getStorageUnit().getUnitNumber() : "", String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Ownership::getSharePercent, Comparator.reverseOrder())
            .thenComparing(o -> o.getOwner().getFullName(), String.CASE_INSENSITIVE_ORDER);

    /** Every share, optionally restricted to one owner / unit / group (only one filter is applied, in that order). */
    public List<OwnershipDTO> getOwnerships(Long ownerId, Long unitId, Long groupId) {
        List<Ownership> rows;
        if (ownerId != null) rows = ownershipRepository.findByOwnerId(ownerId);
        else if (unitId != null) rows = ownershipRepository.findByStorageUnitId(unitId);
        else if (groupId != null) rows = ownershipRepository.findByStorageGroupId(groupId);
        else rows = ownershipRepository.findAll();
        return rows.stream().sorted(BY_TARGET_THEN_SHARE).map(o -> toDto(o, false)).toList();
    }

    public List<OwnershipDTO> getOwnershipsByOwner(Long ownerId) {
        return getOwnerships(ownerId, null, null);
    }

    public Ownership getOwnershipById(Long id) {
        return ownershipRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Ownership not found with id: " + id));
    }

    public OwnershipDTO getOwnershipDtoById(Long id) {
        return toDto(getOwnershipById(id), false);
    }

    /**
     * Owners of a unit after applying the resolution rule: the unit's own shares
     * if it has any, otherwise the shares of its group (flagged as inherited).
     */
    public List<OwnershipDTO> getEffectiveOwnersOfUnit(Long unitId) {
        StorageUnit unit = storageUnitRepository.findById(unitId)
                .orElseThrow(() -> new ResourceNotFoundException("Storage unit not found with id: " + unitId));
        List<Ownership> own = ownershipRepository.findByStorageUnitId(unitId);
        if (!own.isEmpty()) {
            return own.stream().sorted(BY_TARGET_THEN_SHARE).map(o -> toDto(o, false)).toList();
        }
        if (unit.getStorageGroup() == null) return List.of();
        return ownershipRepository.findByStorageGroupId(unit.getStorageGroup().getId()).stream()
                .sorted(BY_TARGET_THEN_SHARE)
                .map(o -> toDto(o, true))
                .toList();
    }

    @Transactional
    public OwnershipDTO createOwnership(OwnershipRequest request) {
        Ownership ownership = new Ownership();
        apply(ownership, request);
        return toDto(ownershipRepository.save(ownership), false);
    }

    @Transactional
    public OwnershipDTO updateOwnership(Long id, OwnershipRequest request) {
        Ownership ownership = getOwnershipById(id);
        apply(ownership, request);
        return toDto(ownershipRepository.save(ownership), false);
    }

    @Transactional
    public void deleteOwnership(Long id) {
        ownershipRepository.delete(getOwnershipById(id));
    }

    /** Removes every unit-level share of a unit (used when the unit itself is deleted). */
    @Transactional
    public void deleteOwnershipsOfUnit(Long unitId) {
        ownershipRepository.deleteByStorageUnitId(unitId);
    }

    private void apply(Ownership ownership, OwnershipRequest request) {
        boolean hasUnit = request.getStorageUnitId() != null;
        boolean hasGroup = request.getStorageGroupId() != null;
        if (hasUnit == hasGroup) {
            throw new BadRequestException("A share must refer to exactly one storage unit or one storage group");
        }
        Owner owner = ownerRepository.findById(request.getOwnerId())
                .orElseThrow(() -> new ResourceNotFoundException("Owner not found with id: " + request.getOwnerId()));

        StorageUnit unit = null;
        StorageGroup group = null;
        if (hasUnit) {
            unit = storageUnitRepository.findById(request.getStorageUnitId())
                    .orElseThrow(() -> new ResourceNotFoundException("Storage unit not found with id: " + request.getStorageUnitId()));
            ownershipRepository.findByOwnerIdAndStorageUnitId(owner.getId(), unit.getId())
                    .filter(other -> !other.getId().equals(ownership.getId()))
                    .ifPresent(other -> {
                        throw new BadRequestException(owner.getFullName() + " already holds a share of unit " + other.getStorageUnit().getUnitNumber());
                    });
        } else {
            group = storageGroupRepository.findById(request.getStorageGroupId())
                    .orElseThrow(() -> new ResourceNotFoundException("Storage group not found with id: " + request.getStorageGroupId()));
            ownershipRepository.findByOwnerIdAndStorageGroupId(owner.getId(), group.getId())
                    .filter(other -> !other.getId().equals(ownership.getId()))
                    .ifPresent(other -> {
                        throw new BadRequestException(owner.getFullName() + " already holds a share of group " + other.getStorageGroup().getName());
                    });
        }

        ownership.setOwner(owner);
        ownership.setStorageUnit(unit);
        ownership.setStorageGroup(group);
        ownership.setSharePercent(request.getSharePercent().setScale(4, RoundingMode.HALF_UP));
        String notes = request.getNotes() == null ? null : request.getNotes().trim();
        ownership.setNotes(notes == null || notes.isEmpty() ? null : notes);
    }

    public OwnershipDTO toDto(Ownership o, boolean inherited) {
        StorageUnit unit = o.getStorageUnit();
        StorageGroup group = o.getStorageGroup();
        BigDecimal share = o.getSharePercent() == null ? null : o.getSharePercent().stripTrailingZeros();
        if (share != null && share.scale() < 0) share = share.setScale(0);
        return OwnershipDTO.builder()
                .id(o.getId())
                .ownerId(o.getOwner().getId())
                .ownerName(o.getOwner().getFullName())
                .storageUnitId(unit != null ? unit.getId() : null)
                .storageUnitNumber(unit != null ? unit.getUnitNumber() : null)
                .storageUnitName(unit != null ? unit.getName() : null)
                .storageGroupId(group != null ? group.getId() : unit != null && unit.getStorageGroup() != null ? unit.getStorageGroup().getId() : null)
                .storageGroupName(group != null ? group.getName() : unit != null && unit.getStorageGroup() != null ? unit.getStorageGroup().getName() : null)
                .sharePercent(share)
                .notes(o.getNotes())
                .inherited(inherited)
                .build();
    }
}
