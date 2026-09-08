package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Las fichas de los ficheros. De quién es cada uno se pregunta por su tabla de
 * relación ({@link ClientDocumentRepository}, {@link RentalDocumentRepository}).
 */
@Repository
public interface DocumentRepository extends JpaRepository<Document, Long> {
}
