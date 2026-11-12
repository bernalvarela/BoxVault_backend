package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.UnitDocumentEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UnitDocumentEntryRepository extends JpaRepository<UnitDocumentEntry, Long> {

    /** Las entradas de una unidad, la más reciente primero. */
    List<UnitDocumentEntry> findByStorageUnitIdOrderByEntryDateDescIdDesc(Long storageUnitId);

    long countByStorageUnitId(Long storageUnitId);
}
