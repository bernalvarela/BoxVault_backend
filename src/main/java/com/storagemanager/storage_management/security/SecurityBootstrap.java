package com.storagemanager.storage_management.security;

import com.storagemanager.storage_management.model.AppUser;
import com.storagemanager.storage_management.model.Role;
import com.storagemanager.storage_management.model.RolePermission;
import com.storagemanager.storage_management.model.enums.AccessArea;
import com.storagemanager.storage_management.model.enums.AccessLevel;
import com.storagemanager.storage_management.repository.AppUserRepository;
import com.storagemanager.storage_management.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Los perfiles de serie y, si no hay ningún usuario, el administrador inicial.
 * <p>
 * El administrador se crea con la contraseña de {@code BOXVAULT_ADMIN_PASSWORD}
 * y marcado para cambiarla al entrar: esa contraseña está en el fichero .env del
 * servidor, así que no puede ser la definitiva. Si ya hay usuarios no se toca
 * nada, de modo que arrancar de nuevo nunca resucita un acceso que se quitó.
 * <p>
 * Los perfiles se crean si faltan, pero no se reescriben: son los de partida, y
 * a partir de ahí mandan los cambios que se hagan desde la aplicación.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class SecurityBootstrap {

    private static final String ADMIN_ROLE = "Administrador";
    private static final String MANAGER_ROLE = "Gestor";
    private static final String READONLY_ROLE = "Consulta";

    private final RoleRepository roles;
    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final SecurityProperties properties;

    @Bean
    public ApplicationRunner securityBootstrapRunner() {
        return args -> bootstrap();
    }

    @Transactional
    public void bootstrap() {
        Role admin = ensureRole(ADMIN_ROLE,
                "Todo, incluidos los usuarios y sus permisos",
                everyArea(AccessLevel.ADMINISTRAR));

        ensureRole(MANAGER_ROLE,
                "Lleva el día a día de todas las unidades; no administra usuarios",
                Map.of(
                        AccessArea.UNIDADES, AccessLevel.ESCRIBIR,
                        AccessArea.CLIENTES, AccessLevel.ESCRIBIR,
                        AccessArea.ALQUILERES, AccessLevel.ESCRIBIR,
                        AccessArea.PAGOS, AccessLevel.ESCRIBIR,
                        AccessArea.GASTOS, AccessLevel.ESCRIBIR,
                        AccessArea.IMPUESTOS, AccessLevel.LEER,
                        AccessArea.PROPIETARIOS, AccessLevel.LEER,
                        AccessArea.USUARIOS, AccessLevel.NINGUNO));

        ensureRole(READONLY_ROLE,
                "Consulta lo de sus unidades, sin cambiar nada",
                Map.of(
                        AccessArea.UNIDADES, AccessLevel.LEER,
                        AccessArea.CLIENTES, AccessLevel.LEER,
                        AccessArea.ALQUILERES, AccessLevel.LEER,
                        AccessArea.PAGOS, AccessLevel.LEER,
                        AccessArea.GASTOS, AccessLevel.LEER,
                        AccessArea.IMPUESTOS, AccessLevel.NINGUNO,
                        AccessArea.PROPIETARIOS, AccessLevel.NINGUNO,
                        AccessArea.USUARIOS, AccessLevel.NINGUNO));

        if (users.count() > 0) return;

        String username = AuthServiceSupport.normalize(properties.getBootstrapUsername());
        AppUser bootstrapAdmin = users.save(AppUser.builder()
                .username(username)
                .fullName("Administrador")
                .role(admin)
                .fullScope(true)
                .active(true)
                .mustChangePassword(true)
                .passwordHash(passwordEncoder.encode(properties.requireBootstrapPassword()))
                .build());
        log.warn("No había ningún usuario: creado '{}' con la contraseña de BOXVAULT_ADMIN_PASSWORD. "
                + "Hay que cambiarla al entrar.", bootstrapAdmin.getUsername());
    }

    private Map<AccessArea, AccessLevel> everyArea(AccessLevel level) {
        Map<AccessArea, AccessLevel> levels = new EnumMap<>(AccessArea.class);
        for (AccessArea area : AccessArea.values()) {
            levels.put(area, level);
        }
        return levels;
    }

    private Role ensureRole(String name, String description, Map<AccessArea, AccessLevel> levels) {
        return roles.findByName(name).orElseGet(() -> {
            Role role = Role.builder()
                    .name(name)
                    .description(description)
                    .systemRole(true)
                    .build();
            List<RolePermission> permissions = new ArrayList<>();
            levels.forEach((area, level) -> {
                if (level != AccessLevel.NINGUNO) {
                    permissions.add(RolePermission.builder().role(role).area(area).level(level).build());
                }
            });
            role.setPermissions(permissions);
            log.info("Perfil creado: {}", name);
            return roles.save(role);
        });
    }

    /** Para no depender del servicio sólo por normalizar un nombre. */
    private static final class AuthServiceSupport {
        static String normalize(String username) {
            return username == null ? "admin" : username.trim().toLowerCase(java.util.Locale.ROOT);
        }
    }
}
