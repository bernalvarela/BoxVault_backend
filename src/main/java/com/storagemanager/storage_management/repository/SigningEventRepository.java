package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.SigningEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SigningEventRepository extends JpaRepository<SigningEvent, Long> {

    /** El registro de una firma, en el orden en que pasó. */
    List<SigningEvent> findBySigningIdOrderByIdAsc(Long signingId);

    /** La última línea, de la que cuelga la siguiente. */
    Optional<SigningEvent> findFirstBySigningIdOrderByIdDesc(Long signingId);
}
