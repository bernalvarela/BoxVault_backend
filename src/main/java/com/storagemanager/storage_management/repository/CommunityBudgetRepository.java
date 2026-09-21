package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.CommunityBudget;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommunityBudgetRepository extends JpaRepository<CommunityBudget, Long> {

    java.util.List<CommunityBudget> findByCommunityIdOrderByYearDesc(Long communityId);

    java.util.Optional<CommunityBudget> findByCommunityIdAndYear(Long communityId, Integer year);
}
