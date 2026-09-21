package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.CommunityEntryDocument;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommunityEntryDocumentRepository extends JpaRepository<CommunityEntryDocument, Long> {

    java.util.List<CommunityEntryDocument> findByEntryId(Long entryId);

    java.util.Optional<CommunityEntryDocument> findByEntryIdAndDocumentId(Long entryId, Long documentId);
}
