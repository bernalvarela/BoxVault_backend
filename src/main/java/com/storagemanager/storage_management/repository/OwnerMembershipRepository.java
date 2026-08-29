package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.OwnerMembership;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface OwnerMembershipRepository extends JpaRepository<OwnerMembership, Long> {
    List<OwnerMembership> findByEntityId(Long entityId);
    List<OwnerMembership> findByMemberId(Long memberId);
    long countByMemberId(Long memberId);

    @Transactional
    void deleteByEntityId(Long entityId);
}
