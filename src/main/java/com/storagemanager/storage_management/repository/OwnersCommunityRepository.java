package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.OwnersCommunity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OwnersCommunityRepository extends JpaRepository<OwnersCommunity, Long> {

    java.util.List<OwnersCommunity> findAllByOrderByNameAsc();
}
