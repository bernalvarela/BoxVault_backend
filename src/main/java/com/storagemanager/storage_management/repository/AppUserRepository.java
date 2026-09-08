package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    /** El usuario que entra. El nombre se guarda ya en minúsculas. */
    Optional<AppUser> findByUsername(String username);

    boolean existsByUsername(String username);

    /** El listado de la pantalla de administración. */
    List<AppUser> findAllByOrderByFullNameAsc();

    /** Para saber si hay que crear el administrador inicial al arrancar. */
    long countByActiveTrue();
}
