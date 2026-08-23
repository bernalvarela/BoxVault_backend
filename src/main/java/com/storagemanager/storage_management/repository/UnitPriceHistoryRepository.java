package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.UnitPriceHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UnitPriceHistoryRepository extends JpaRepository<UnitPriceHistory, Long> {

    List<UnitPriceHistory> findByStorageUnitIdOrderByEffectiveFromAscIdAsc(Long storageUnitId);
}
