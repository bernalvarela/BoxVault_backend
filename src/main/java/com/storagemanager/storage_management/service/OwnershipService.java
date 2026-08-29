package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.dto.OwnershipDTO;
import com.storagemanager.storage_management.dto.OwnershipRequest;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.Owner;
import com.storagemanager.storage_management.model.Ownership;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.repository.OwnerRepository;
import com.storagemanager.storage_management.repository.OwnershipRepository;
import com.storagemanager.storage_management.repository.StorageUnitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shares of owners in units. See {@link Ownership} for the resolution rule (a
 * unit without shares of its own inherits its parent's).
 */
@Service
@RequiredArgsConstructor
public class OwnershipService {

    private final OwnershipRepository ownershipRepository;
    private final OwnerRepository ownerRepository;
    private final StorageUnitRepository storageUnitRepository;

    private static final Comparator<Ownership> BY_UNIT_THEN_SHARE = Comparator
            .comparing((Ownership o) -> o.getStorageUnit().getRoot().unitNumber().length())
            .thenComparing(o -> o.getStorageUnit().getRoot().unitNumber(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(o -> o.getStorageUnit().getParent() == null ? 0 : 1)
            .thenComparing(o -> o.getStorageUnit().getUnitNumber().length())
            .thenComparing(o -> o.getStorageUnit().getUnitNumber(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Ownership::getSharePercent, Comparator.reverseOrder())
            .thenComparing(o -> o.getOwner().getFullName(), String.CASE_INSENSITIVE_ORDER);

    // ------------------------------------------------------------------
    // Resolution helpers (shared with the tax reports)
    // ------------------------------------------------------------------

    /** The shares that apply to a unit and the unit that actually holds them (the unit itself or an ancestor). */
    public record Effective(List<Ownership> shares, StorageUnit holder) {
        public boolean inherited(StorageUnit unit) {
            return holder != null && !holder.getId().equals(unit.getId());
        }
    }

    /** Shares indexed by unit id. */
    public static Map<Long, List<Ownership>> indexByUnit(List<Ownership> all) {
        Map<Long, List<Ownership>> byUnit = new HashMap<>();
        for (Ownership o : all) {
            byUnit.computeIfAbsent(o.getStorageUnit().getId(), k -> new ArrayList<>()).add(o);
        }
        return byUnit;
    }

    /** Walks up from the unit to the first ancestor (or the unit itself) that has shares. */
    public static Effective resolve(StorageUnit unit, Map<Long, List<Ownership>> byUnit) {
        int guard = 0;
        for (StorageUnit u = unit; u != null && guard++ < 32; u = u.getParent()) {
            List<Ownership> own = byUnit.getOrDefault(u.getId(), List.of());
            if (!own.isEmpty()) return new Effective(own, u);
        }
        return new Effective(List.of(), null);
    }

    /** Effective share (%) of an owner in a unit, or null when they hold none. */
    public static BigDecimal shareOf(Long ownerId, StorageUnit unit, Map<Long, List<Ownership>> byUnit) {
        for (Ownership o : resolve(unit, byUnit).shares()) {
            if (o.getOwner().getId().equals(ownerId)) return o.getSharePercent();
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    /** Every share, optionally restricted to one owner / unit (only one filter is applied, in that order). */
    public List<OwnershipDTO> getOwnerships(Long ownerId, Long unitId) {
        List<Ownership> rows;
        if (ownerId != null) rows = ownershipRepository.findByOwnerId(ownerId);
        else if (unitId != null) rows = ownershipRepository.findByStorageUnitId(unitId);
        else rows = ownershipRepository.findAll();
        return rows.stream().sorted(BY_UNIT_THEN_SHARE).map(o -> toDto(o, null)).toList();
    }

    public List<OwnershipDTO> getOwnershipsByOwner(Long ownerId) {
        return getOwnerships(ownerId, null);
    }

    public Ownership getOwnershipById(Long id) {
        return ownershipRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Ownership not found with id: " + id));
    }

    public OwnershipDTO getOwnershipDtoById(Long id) {
        return toDto(getOwnershipById(id), null);
    }

    /** Owners of a unit after applying the resolution rule (inherited shares flagged). */
    public List<OwnershipDTO> getEffectiveOwnersOfUnit(Long unitId) {
        StorageUnit unit = storageUnitRepository.findById(unitId)
                .orElseThrow(() -> new ResourceNotFoundException("Storage unit not found with id: " + unitId));
        Effective effective = resolve(unit, indexByUnit(ownershipRepository.findAll()));
        StorageUnit from = effective.inherited(unit) ? effective.holder() : null;
        return effective.shares().stream()
                .sorted(BY_UNIT_THEN_SHARE)
                .map(o -> toDto(o, from))
                .toList();
    }

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    @Transactional
    public OwnershipDTO createOwnership(OwnershipRequest request) {
        Ownership ownership = new Ownership();
        apply(ownership, request);
        return toDto(ownershipRepository.save(ownership), null);
    }

    @Transactional
    public OwnershipDTO updateOwnership(Long id, OwnershipRequest request) {
        Ownership ownership = getOwnershipById(id);
        apply(ownership, request);
        return toDto(ownershipRepository.save(ownership), null);
    }

    @Transactional
    public void deleteOwnership(Long id) {
        ownershipRepository.delete(getOwnershipById(id));
    }

    /** Removes every share of a unit (used when the unit itself is deleted). */
    @Transactional
    public void deleteOwnershipsOfUnit(Long unitId) {
        ownershipRepository.deleteByStorageUnitId(unitId);
    }

    private void apply(Ownership ownership, OwnershipRequest request) {
        Owner owner = ownerRepository.findById(request.getOwnerId())
                .orElseThrow(() -> new ResourceNotFoundException("Owner not found with id: " + request.getOwnerId()));
        StorageUnit unit = storageUnitRepository.findById(request.getStorageUnitId())
                .orElseThrow(() -> new ResourceNotFoundException("Storage unit not found with id: " + request.getStorageUnitId()));
        ownershipRepository.findByOwnerIdAndStorageUnitId(owner.getId(), unit.getId())
                .filter(other -> !other.getId().equals(ownership.getId()))
                .ifPresent(other -> {
                    throw new BadRequestException(owner.getFullName() + " already holds a share of unit " + unit.getUnitNumber());
                });
        ownership.setOwner(owner);
        ownership.setStorageUnit(unit);
        ownership.setSharePercent(request.getSharePercent().setScale(4, RoundingMode.HALF_UP));
        String notes = request.getNotes() == null ? null : request.getNotes().trim();
        ownership.setNotes(notes == null || notes.isEmpty() ? null : notes);
    }

    /** DTO of a share; {@code inheritedFrom} is the ancestor holding it when returned for a descendant unit. */
    public OwnershipDTO toDto(Ownership o, StorageUnit inheritedFrom) {
        StorageUnit unit = o.getStorageUnit();
        StorageUnit parent = unit.getParent();
        BigDecimal share = o.getSharePercent() == null ? null : o.getSharePercent().stripTrailingZeros();
        if (share != null && share.scale() < 0) share = share.setScale(0);
        return OwnershipDTO.builder()
                .id(o.getId())
                .ownerId(o.getOwner().getId())
                .ownerName(o.getOwner().getFullName())
                .ownerType(o.getOwner().getType())
                .storageUnitId(unit.getId())
                .storageUnitNumber(unit.getUnitNumber())
                .storageUnitName(unit.getName())
                .parentUnitId(parent != null ? parent.getId() : null)
                .parentUnitNumber(parent != null ? parent.getUnitNumber() : null)
                .parentUnitName(parent != null ? parent.getName() : null)
                .sharePercent(share)
                .notes(o.getNotes())
                .inherited(inheritedFrom != null)
                .inheritedFromUnitId(inheritedFrom != null ? inheritedFrom.getId() : null)
                .inheritedFromUnitNumber(inheritedFrom != null ? inheritedFrom.getUnitNumber() : null)
                .build();
    }
}
