package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.CommunityEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommunityEntryRepository extends JpaRepository<CommunityEntry, Long> {

    java.util.List<CommunityEntry> findByCommunityIdOrderByEntryDateDescIdDesc(Long communityId);

    java.util.List<CommunityEntry> findByStorageUnitId(Long storageUnitId);
}
