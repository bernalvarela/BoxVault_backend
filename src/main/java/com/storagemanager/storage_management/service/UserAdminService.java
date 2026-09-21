package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.dto.UserDTOs.RoleDTO;
import com.storagemanager.storage_management.dto.UserDTOs.UserDTO;
import com.storagemanager.storage_management.dto.UserDTOs.UserRequest;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.*;
import com.storagemanager.storage_management.model.enums.AccessArea;
import com.storagemanager.storage_management.model.enums.AccessLevel;
import com.storagemanager.storage_management.repository.*;
import com.storagemanager.storage_management.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * El mantenimiento de los usuarios: altas, permisos, bajas y reinicio de
 * contraseñas. Todo esto lo hace un administrador; el propio usuario sólo cambia
 * su contraseña, y de eso se encarga {@link AuthService}.
 * <p>
 * Dos reglas que se comprueban aquí y no en la pantalla, porque son las que
 * pueden dejar la aplicación sin dueño: nadie se quita a sí mismo la
 * administración ni se da de baja a sí mismo, y siempre tiene que quedar al
 * menos un administrador de alta.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAdminService {

    private final AppUserRepository users;
    private final RoleRepository roles;
    private final UserUnitScopeRepository unitScopes;
    private final BuildingRepository buildings;
    private final StorageUnitRepository storageUnits;
    private final PasswordEncoder passwordEncoder;

    public List<RoleDTO> getRoles() {
        return roles.findAllByOrderByNameAsc().stream().map(RoleDTO::of).toList();
    }

    public List<UserDTO> getUsers() {
        return users.findAllByOrderByFullNameAsc().stream()
                .map(user -> UserDTO.of(user, scopeIdsOf(user.getId()), buildingScopeIdsOf(user.getId())))
                .toList();
    }

    public UserDTO getUser(Long id) {
        AppUser user = requireUser(id);
        return UserDTO.of(user, scopeIdsOf(id), buildingScopeIdsOf(id));
    }

    @Transactional
    public UserDTO create(UserRequest request) {
        String username = AuthService.normalize(request.username());
        if (users.existsByUsername(username)) {
            throw new BadRequestException("Ya hay un usuario con el nombre '" + username + "'");
        }
        AuthService.validatePassword(request.password());

        AppUser user = AppUser.builder()
                .username(username)
                .fullName(request.fullName())
                .email(request.email())
                .role(requireRole(request.roleId()))
                .fullScope(request.fullScope())
                .active(request.active())
                // La contraseña la ha puesto otro: la cambia al entrar.
                .mustChangePassword(true)
                .passwordHash(passwordEncoder.encode(request.password()))
                .build();
        applyOverrides(user, request.overrides());
        AppUser saved = users.save(user);
        replaceUnitScopes(saved, request.unitScopeIds(), request.buildingScopeIds());
        log.info("Usuario '{}' creado con el perfil {}", saved.getUsername(), saved.getRole().getName());
        return UserDTO.of(saved, scopeIdsOf(saved.getId()), buildingScopeIdsOf(saved.getId()));
    }

    @Transactional
    public UserDTO update(Long id, UserRequest request, CurrentUser current) {
        AppUser user = requireUser(id);
        String username = AuthService.normalize(request.username());
        if (!username.equals(user.getUsername()) && users.existsByUsername(username)) {
            throw new BadRequestException("Ya hay un usuario con el nombre '" + username + "'");
        }

        boolean losesAdmin = !grantsAdmin(request);
        if (id.equals(current.id()) && (losesAdmin || !request.active())) {
            throw new BadRequestException("No puedes quitarte a ti mismo la administración ni darte de baja");
        }

        user.setUsername(username);
        user.setFullName(request.fullName());
        user.setEmail(request.email());
        user.setRole(requireRole(request.roleId()));
        user.setFullScope(request.fullScope());
        boolean wasActive = user.isActive();
        user.setActive(request.active());
        applyOverrides(user, request.overrides());
        replaceUnitScopes(user, request.unitScopeIds(), request.buildingScopeIds());

        // Cambiarle los permisos o darle de baja tiene que notarse ya, no cuando
        // le caduque el token que lleva en el navegador.
        user.setTokensValidFrom(LocalDateTime.now());

        requireSomeAdminLeft(id, request);
        if (wasActive && !request.active()) {
            log.info("Usuario '{}' dado de baja", user.getUsername());
        }
        return UserDTO.of(user, scopeIdsOf(id), buildingScopeIdsOf(id));
    }

    /** Reinicia la contraseña de otro: la nueva es provisional y hay que cambiarla. */
    @Transactional
    public void resetPassword(Long id, String newPassword) {
        AuthService.validatePassword(newPassword);
        AppUser user = requireUser(id);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(true);
        user.setTokensValidFrom(LocalDateTime.now());
        log.info("Contraseña de '{}' reiniciada por un administrador", user.getUsername());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private boolean grantsAdmin(UserRequest request) {
        AccessLevel fromOverride = request.overrides() == null
                ? null : request.overrides().get(AccessArea.USUARIOS);
        if (fromOverride != null) return fromOverride.allows(AccessLevel.ADMINISTRAR);
        return requireRole(request.roleId()).getPermissions().stream()
                .anyMatch(p -> p.getArea() == AccessArea.USUARIOS && p.getLevel().allows(AccessLevel.ADMINISTRAR));
    }

    /** Nunca puede quedarse la aplicación sin nadie que administre usuarios. */
    private void requireSomeAdminLeft(Long changedId, UserRequest request) {
        boolean anyAdmin = users.findAllByOrderByFullNameAsc().stream()
                .anyMatch(u -> u.isActive() && CurrentUser.of(u).isAdmin());
        if (!anyAdmin) {
            throw new BadRequestException("Tiene que quedar al menos un administrador de alta");
        }
    }

    private void applyOverrides(AppUser user, Map<AccessArea, AccessLevel> overrides) {
        user.getPermissions().clear();
        if (overrides == null) return;
        overrides.forEach((area, level) -> user.getPermissions().add(
                UserPermission.builder().user(user).area(area).level(level).build()));
    }

    /**
     * Deja al usuario exactamente con estas concesiones.
     * <p>
     * Una concesión es de una unidad o de un edificio entero, nunca de las dos
     * cosas. Lo normal es el edificio: quien lo administra ve sus unidades de
     * hoy y las que se creen mañana sin volver a tocar los permisos. Las
     * concesiones sueltas siguen valiendo para el caso fino —dar dos trasteros
     * y nada más—.
     */
    private void replaceUnitScopes(AppUser user, List<Long> unitIds, List<Long> buildingIds) {
        unitScopes.deleteByUserId(user.getId());
        List<UserUnitScope> scopes = new ArrayList<>();
        if (unitIds != null) {
            for (Long unitId : unitIds) {
                StorageUnit unit = storageUnits.findById(unitId)
                        .orElseThrow(() -> new ResourceNotFoundException("Storage unit not found with id: " + unitId));
                scopes.add(UserUnitScope.builder().user(user).storageUnit(unit).build());
            }
        }
        if (buildingIds != null) {
            for (Long buildingId : buildingIds) {
                Building building = buildings.findById(buildingId)
                        .orElseThrow(() -> new ResourceNotFoundException("Building not found with id: " + buildingId));
                scopes.add(UserUnitScope.builder().user(user).building(building).build());
            }
        }
        if (!scopes.isEmpty()) unitScopes.saveAll(scopes);
    }

    private List<Long> scopeIdsOf(Long userId) {
        return unitScopes.findByUserId(userId).stream()
                .map(UserUnitScope::getStorageUnit)
                .filter(java.util.Objects::nonNull)
                .map(StorageUnit::getId)
                .toList();
    }

    private List<Long> buildingScopeIdsOf(Long userId) {
        return unitScopes.findByUserId(userId).stream()
                .map(UserUnitScope::getBuilding)
                .filter(java.util.Objects::nonNull)
                .map(Building::getId)
                .toList();
    }

    private AppUser requireUser(Long id) {
        return users.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));
    }

    private Role requireRole(Long id) {
        return roles.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found with id: " + id));
    }
}
