package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.TaxFiling;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TaxFilingRepository extends JpaRepository<TaxFiling, Long> {
    List<TaxFiling> findAllByOrderByYearDescQuarterDescFiledDateDescIdDesc();
}
