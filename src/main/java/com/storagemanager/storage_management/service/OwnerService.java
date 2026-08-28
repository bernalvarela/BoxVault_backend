package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.dto.OwnerDTO;
import com.storagemanager.storage_management.dto.OwnerRequest;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.Owner;
import com.storagemanager.storage_management.repository.OwnerRepository;
import com.storagemanager.storage_management.repository.OwnershipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OwnerService {

    private final OwnerRepository ownerRepository;
    private final OwnershipRepository ownershipRepository;
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
        Owner owner = Owner.builder().fullName(name).build();
        apply(owner, request);
        return toDto(ownerRepository.save(owner));
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
        apply(owner, request);
        return toDto(ownerRepository.save(owner));
    }

    @Transactional
    public void deleteOwner(Long id) {
        Owner owner = getOwnerById(id);
        long shares = ownershipRepository.countByOwnerId(id);
        if (shares > 0) {
            throw new BadRequestException("Cannot delete owner '" + owner.getFullName()
                    + "' while they still hold " + shares + " share(s). Remove their participations first.");
        }
        ownerRepository.delete(owner);
    }

    private static void apply(Owner owner, OwnerRequest request) {
        owner.setDocumentId(trimToNull(request.getDocumentId()));
        owner.setEmail(trimToNull(request.getEmail()));
        owner.setPhone(trimToNull(request.getPhone()));
        owner.setBankAccount(trimToNull(request.getBankAccount()));
        owner.setNotes(trimToNull(request.getNotes()));
    }

    public OwnerDTO toDto(Owner owner) {
        return OwnerDTO.builder()
                .id(owner.getId())
                .fullName(owner.getFullName())
                .documentId(owner.getDocumentId())
                .email(owner.getEmail())
                .phone(owner.getPhone())
                .bankAccount(owner.getBankAccount())
                .notes(owner.getNotes())
                .createdAt(owner.getCreatedAt())
                .updatedAt(owner.getUpdatedAt())
                .ownerships(ownershipService.getOwnershipsByOwner(owner.getId()))
                .build();
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
