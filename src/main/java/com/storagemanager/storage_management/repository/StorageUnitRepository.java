package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.model.enums.UnitKind;
import com.storagemanager.storage_management.model.enums.UnitStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StorageUnitRepository extends JpaRepository<StorageUnit, Long> {

    /**
     * Suelta la plantilla de las unidades que la tuvieran fijada: pasan a heredar
     * en vez de quedarse apuntando a una que ya no existe.
     */
    @Modifying
    @Query("UPDATE StorageUnit u SET u.contractTemplate = NULL WHERE u.contractTemplate.id = :templateId")
    void clearContractTemplate(@Param("templateId") Long templateId);
    Optional<StorageUnit> findByUnitNumber(String unitNumber);
    List<StorageUnit> findByStatus(UnitStatus status);
    long countByStatus(UnitStatus status);
    List<StorageUnit> findByKind(UnitKind kind);
    List<StorageUnit> findByKindAndStatus(UnitKind kind, UnitStatus status);
    List<StorageUnit> findByKindIsNull();

    /** Units directly inside the given unit (a local). */
    List<StorageUnit> findByParentId(Long parentId);
    /** Top-level units (no parent): the roots the statistics filter by. */
    List<StorageUnit> findByParentIsNull();
    long countByParentId(Long parentId);
}
