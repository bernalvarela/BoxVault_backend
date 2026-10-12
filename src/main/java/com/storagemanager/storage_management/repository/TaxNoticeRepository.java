package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.TaxNotice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TaxNoticeRepository extends JpaRepository<TaxNotice, Long> {

    /** Todas, la última recibida primero. */
    List<TaxNotice> findAllByOrderByReceivedOnDescIdDesc();

    /** Las que traen causa de una declaración presentada. */
    List<TaxNotice> findByFilingId(Long filingId);
}
