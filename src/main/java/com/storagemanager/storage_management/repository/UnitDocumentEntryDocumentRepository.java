package com.storagemanager.storage_management.repository;

import com.storagemanager.storage_management.model.UnitDocumentEntryDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface UnitDocumentEntryDocumentRepository extends JpaRepository<UnitDocumentEntryDocument, Long> {

    /** Los ficheros de una entrada, por orden de subida. */
    List<UnitDocumentEntryDocument> findByEntryIdOrderByDocumentUploadedAtAscDocumentIdAsc(Long entryId);

    /** Los ficheros de varias entradas a la vez, para listar las de una unidad sin una consulta por entrada. */
    List<UnitDocumentEntryDocument> findByEntryIdInOrderByDocumentUploadedAtAscDocumentIdAsc(Collection<Long> entryIds);

    /** La relación con ese documento, sólo si es de esa entrada: la url lleva los dos. */
    Optional<UnitDocumentEntryDocument> findByEntryIdAndDocumentId(Long entryId, Long documentId);
}
