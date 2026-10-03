package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.BankVocabularyEntry;
import com.storagemanager.storage_management.model.enums.BankVocabularyList;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BankVocabularyRepository extends JpaRepository<BankVocabularyEntry, Long> {

    List<BankVocabularyEntry> findAllByOrderByListKeyAscSortOrderAsc();

    boolean existsByListKey(BankVocabularyList list);

    @Modifying
    @Query("DELETE FROM BankVocabularyEntry e WHERE e.listKey = :list")
    void deleteByList(@Param("list") BankVocabularyList list);
}
