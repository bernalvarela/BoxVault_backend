package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.RentalParty;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RentalPartyRepository extends JpaRepository<RentalParty, Long> {

    /** Los contratos que ha firmado esa persona, sea como arrendataria o avalando. */
    List<RentalParty> findByClientId(Long clientId);
}
