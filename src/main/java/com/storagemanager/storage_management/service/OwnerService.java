package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.dto.OwnerDTO;
import com.storagemanager.storage_management.dto.OwnerMembershipDTO;
import com.storagemanager.storage_management.dto.OwnerRequest;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.Owner;
import com.storagemanager.storage_management.model.OwnerMembership;
import com.storagemanager.storage_management.model.enums.OwnerType;
import com.storagemanager.storage_management.repository.OwnerMembershipRepository;
import com.storagemanager.storage_management.repository.OwnerRepository;
import com.storagemanager.storage_management.repository.OwnershipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class OwnerService {

    private final OwnerRepository ownerRepository;
    private final OwnershipRepository ownershipRepository;
    private final OwnerMembershipRepository ownerMembershipRepository;
    private final OwnershipService ownershipService;

    public List<OwnerDTO> getAllOwners() {
        return ownerRepository.findAllByOrderByFullNameAsc().stream()
                .map(this::toDto)
                .toList();
    }

    public Owner getOwnerById(Long id) {
        return ownerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Owner not found with id: " + id));
    }

    public OwnerDTO getOwnerDtoById(Long id) {
        return toDto(getOwnerById(id));
    }

    @Transactional
    public OwnerDTO createOwner(OwnerRequest request) {
        String name = request.getFullName().trim();
        if (ownerRepository.findByFullNameIgnoreCase(name).isPresent()) {
            throw new BadRequestException("Owner already exists: " + name);
        }
        Owner owner = Owner.builder()
                .fullName(name)
                .type(request.getType() != null ? request.getType() : OwnerType.PERSON)
                .build();
        apply(owner, request);
        Owner saved = ownerRepository.save(owner);
        if (saved.isEntity() && request.getMembers() != null) {
            replaceMembers(saved, request.getMembers());
        }
        return toDto(saved);
    }

    @Transactional
    public OwnerDTO updateOwner(Long id, OwnerRequest request) {
        Owner owner = getOwnerById(id);
        String name = request.getFullName().trim();
        ownerRepository.findByFullNameIgnoreCase(name)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new BadRequestException("Owner already exists: " + name);
                });
        owner.setFullName(name);
        if (request.getType() != null && request.getType() != owner.getType()) {
            if (request.getType() == OwnerType.PERSON && !ownerMembershipRepository.findByEntityId(id).isEmpty()) {
                throw new BadRequestException("Remove the members of '" + owner.getFullName() + "' before turning it into a person");
            }
            if (request.getType() == OwnerType.COMUNIDAD_DE_BIENES && ownerMembershipRepository.countByMemberId(id) > 0) {
                throw new BadRequestException("'" + owner.getFullName() + "' is a member of an entity and cannot become one");
            }
            owner.setType(request.getType());
        }
        apply(owner, request);
        Owner saved = ownerRepository.save(owner);
        if (saved.isEntity() && request.getMembers() != null) {
            replaceMembers(saved, request.getMembers());
        }
        return toDto(saved);
    }

    @Transactional
    public void deleteOwner(Long id) {
        Owner owner = getOwnerById(id);
        long shares = ownershipRepository.countByOwnerId(id);
        if (shares > 0) {
            throw new BadRequestException("Cannot delete owner '" + owner.getFullName()
                    + "' while they still hold " + shares + " share(s). Remove their participations first.");
        }
        long memberships = ownerMembershipRepository.countByMemberId(id);
        if (memberships > 0) {
            throw new BadRequestException("Cannot delete owner '" + owner.getFullName()
                    + "' while they are a member of " + memberships + " entity(ies). Remove them from the entity first.");
        }
        ownerMembershipRepository.deleteByEntityId(id);
        ownerRepository.delete(owner);
    }

    /** Replaces the members of a comunidad de bienes with the given list. */
    private void replaceMembers(Owner entity, List<OwnerRequest.MemberRequest> members) {
        Set<Long> seen = new HashSet<>();
        List<OwnerMembership> rows = new java.util.ArrayList<>();
        for (OwnerRequest.MemberRequest m : members) {
            if (m.getOwnerId().equals(entity.getId())) {
                throw new BadRequestException("An entity cannot be a member of itself");
            }
            if (!seen.add(m.getOwnerId())) {
                throw new BadRequestException("A member is listed twice");
            }
            Owner member = getOwnerById(m.getOwnerId());
            if (member.isEntity()) {
                throw new BadRequestException("'" + member.getFullName() + "' is an entity; only persons can be members");
            }
            rows.add(OwnerMembership.builder()
                    .entity(entity)
                    .member(member)
                    .sharePercent(m.getSharePercent().setScale(4, RoundingMode.HALF_UP))
                    .notes(trimToNull(m.getNotes()))
                    .build());
        }
        ownerMembershipRepository.deleteByEntityId(entity.getId());
        ownerMembershipRepository.saveAll(rows);
    }

    private static void apply(Owner owner, OwnerRequest request) {
        owner.setDocumentId(trimToNull(request.getDocumentId()));
        owner.setEmail(trimToNull(request.getEmail()));
        owner.setPhone(trimToNull(request.getPhone()));
        owner.setBankAccount(trimToNull(request.getBankAccount()));
        owner.setNotes(trimToNull(request.getNotes()));
    }

    public static OwnerMembershipDTO toDto(OwnerMembership m) {
        return OwnerMembershipDTO.builder()
                .id(m.getId())
                .entityId(m.getEntity().getId())
                .entityName(m.getEntity().getFullName())
                .memberId(m.getMember().getId())
                .memberName(m.getMember().getFullName())
                .sharePercent(m.getSharePercent())
                .notes(m.getNotes())
                .build();
    }

    public OwnerDTO toDto(Owner owner) {
        List<OwnerMembershipDTO> members = owner.isEntity()
                ? ownerMembershipRepository.findByEntityId(owner.getId()).stream()
                        .sorted(java.util.Comparator.comparing(OwnerMembership::getSharePercent).reversed()
                                .thenComparing(m -> m.getMember().getFullName(), String.CASE_INSENSITIVE_ORDER))
                        .map(OwnerService::toDto).toList()
                : List.of();
        List<OwnerMembershipDTO> memberOf = owner.isEntity() ? List.of()
                : ownerMembershipRepository.findByMemberId(owner.getId()).stream()
                        .sorted(java.util.Comparator.comparing((OwnerMembership m) -> m.getEntity().getFullName(), String.CASE_INSENSITIVE_ORDER))
                        .map(OwnerService::toDto).toList();
        return OwnerDTO.builder()
                .id(owner.getId())
                .fullName(owner.getFullName())
                .type(owner.getType())
                .documentId(owner.getDocumentId())
                .email(owner.getEmail())
                .phone(owner.getPhone())
                .bankAccount(owner.getBankAccount())
                .notes(owner.getNotes())
                .createdAt(owner.getCreatedAt())
                .updatedAt(owner.getUpdatedAt())
                .ownerships(ownershipService.getOwnershipsByOwner(owner.getId()))
                .members(members)
                .memberOf(memberOf)
                .build();
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
