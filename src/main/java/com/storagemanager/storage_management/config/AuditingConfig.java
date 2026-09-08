package com.storagemanager.storage_management.config;

import com.storagemanager.storage_management.security.Authenticated;
import com.storagemanager.storage_management.security.CurrentUser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.util.Optional;

/**
 * Quién crea y quién cambia cada fila. Las entidades que llevan
 * {@link CreatedBy} y {@link LastModifiedBy} se rellenan solas con el usuario de
 * la petición, sin que los servicios tengan que acordarse.
 * <p>
 * Se guarda el nombre de usuario y no su id: lo que se quiere al mirar una fila
 * meses después es leer quién fue, y el usuario no se borra nunca (se da de
 * baja), así que el nombre sigue queriendo decir algo.
 * <p>
 * Lo que hace la propia aplicación al arrancar —crear los perfiles, el
 * administrador inicial, registrar las declaraciones— no tiene usuario detrás y
 * se queda como "sistema".
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class AuditingConfig {

    @Bean
    public AuditorAware<String> auditorAware() {
        return () -> {
            CurrentUser user = Authenticated.userOrNull();
            return Optional.of(user != null ? user.username() : "sistema");
        };
    }
}
