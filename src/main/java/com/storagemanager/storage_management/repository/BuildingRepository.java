package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.Building;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BuildingRepository extends JpaRepository<Building, Long> {

    java.util.Optional<Building> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);

    java.util.List<Building> findAllByOrderByNameAsc();
}
