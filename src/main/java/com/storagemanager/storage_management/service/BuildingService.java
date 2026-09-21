package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.dto.BuildingDTOs.BuildingDTO;
import com.storagemanager.storage_management.dto.BuildingDTOs.BuildingRequest;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.Building;
import com.storagemanager.storage_management.model.OwnersCommunity;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.repository.BuildingRepository;
import com.storagemanager.storage_management.repository.OwnersCommunityRepository;
import com.storagemanager.storage_management.repository.StorageUnitRepository;
import com.storagemanager.storage_management.security.UnitScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Los edificios: el nivel de arriba del que cuelga todo.
 * <p>
 * Se ven los que contengan alguna unidad del ámbito del usuario. Quien
 * administra un edificio no sabe siquiera que existe el otro, que es la razón de
 * ser de todo esto.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuildingService {

    private final BuildingRepository buildings;
    private final OwnersCommunityRepository communities;
    private final StorageUnitRepository units;
    private final UnitScope unitScope;

    public List<BuildingDTO> list() {
        Map<Long, List<StorageUnit>> byBuilding = units.findAll().stream()
                .filter(unit -> unit.getBuilding() != null)
                .collect(Collectors.groupingBy(unit -> unit.getBuilding().getId()));

        Set<Long> visible = visibleBuildingIds();
        return buildings.findAllByOrderByNameAsc().stream()
                .filter(building -> visible == null || visible.contains(building.getId()))
                .map(building -> {
                    List<StorageUnit> own = byBuilding.getOrDefault(building.getId(), List.of());
                    return BuildingDTO.of(building, own.size(), coefficientTotal(own));
                })
                .toList();
    }

    public Building get(Long id) {
        Building building = require(id);
        Set<Long> visible = visibleBuildingIds();
        if (visible != null && !visible.contains(id)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Ese edificio no está en tu ámbito");
        }
        return building;
    }

    @Transactional
    public BuildingDTO create(BuildingRequest request) {
        if (buildings.existsByNameIgnoreCase(request.getName().trim())) {
            throw new BadRequestException("Ya hay un edificio que se llama " + request.getName().trim());
        }
        Building building = buildings.save(Building.builder()
                .name(request.getName().trim())
                .address(trimToNull(request.getAddress()))
                .city(trimToNull(request.getCity()))
                .community(communityOf(request.getCommunityId()))
                .notes(trimToNull(request.getNotes()))
                .build());
        log.info("Creado el edificio {} ({})", building.getName(), building.getId());
        return BuildingDTO.of(building, 0, BigDecimal.ZERO);
    }

    @Transactional
    public BuildingDTO update(Long id, BuildingRequest request) {
        Building building = get(id);
        buildings.findByNameIgnoreCase(request.getName().trim())
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new BadRequestException("Ya hay un edificio que se llama " + request.getName().trim());
                });

        building.setName(request.getName().trim());
        building.setAddress(trimToNull(request.getAddress()));
        building.setCity(trimToNull(request.getCity()));
        building.setCommunity(communityOf(request.getCommunityId()));
        building.setNotes(trimToNull(request.getNotes()));
        buildings.save(building);

        List<StorageUnit> own = unitsOf(id);
        return BuildingDTO.of(building, own.size(), coefficientTotal(own));
    }

    /**
     * Borra el edificio. No se puede si todavía cuelga algo de él: un edificio
     * vacío es un error de captura, pero uno con unidades es el sitio donde
     * están, y borrarlo dejaría media aplicación apuntando al aire.
     */
    @Transactional
    public void delete(Long id) {
        Building building = get(id);
        List<StorageUnit> own = unitsOf(id);
        if (!own.isEmpty()) {
            throw new BadRequestException("El edificio " + building.getName() + " tiene " + own.size()
                    + " unidad(es). Muévelas a otro edificio antes de borrarlo.");
        }
        buildings.delete(building);
        log.info("Borrado el edificio {} ({})", building.getName(), id);
    }

    /** Las unidades raíz del edificio: las que llevan su bandera. */
    public List<StorageUnit> unitsOf(Long buildingId) {
        return units.findAll().stream()
                .filter(unit -> unit.getBuilding() != null && unit.getBuilding().getId().equals(buildingId))
                .toList();
    }

    /**
     * Los edificios que el usuario alcanza, deducidos de sus unidades.
     * {@code null} = todos.
     */
    public Set<Long> visibleBuildingIds() {
        Set<Long> accessible = unitScope.accessibleUnitIds();
        if (accessible == null) return null;
        return units.findAll().stream()
                .filter(unit -> accessible.contains(unit.getId()))
                .map(StorageUnit::building)
                .filter(java.util.Objects::nonNull)
                .map(Building::getId)
                .collect(Collectors.toSet());
    }

    private BigDecimal coefficientTotal(List<StorageUnit> own) {
        return own.stream()
                .map(StorageUnit::getParticipationCoefficient)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private OwnersCommunity communityOf(Long communityId) {
        if (communityId == null) return null;
        return communities.findById(communityId)
                .orElseThrow(() -> new ResourceNotFoundException("Community not found with id: " + communityId));
    }

    private Building require(Long id) {
        return buildings.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Building not found with id: " + id));
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
