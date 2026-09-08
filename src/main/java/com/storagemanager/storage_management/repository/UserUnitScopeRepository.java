package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.UserUnitScope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserUnitScopeRepository extends JpaRepository<UserUnitScope, Long> {

    /** Las unidades concedidas a un usuario (la raíz de cada subárbol). */
    List<UserUnitScope> findByUserId(Long userId);

    void deleteByUserId(Long userId);
}
