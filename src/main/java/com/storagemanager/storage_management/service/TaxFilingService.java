package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.dto.TaxFilingDTO;
import com.storagemanager.storage_management.dto.TaxFilingRequest;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.Owner;
import com.storagemanager.storage_management.model.TaxFiling;
import com.storagemanager.storage_management.model.enums.TaxModel;
import com.storagemanager.storage_management.repository.OwnerRepository;
import com.storagemanager.storage_management.repository.TaxFilingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.util.List;

/** Register of filed tax returns (see {@link TaxFiling}). */
@Service
@RequiredArgsConstructor
public class TaxFilingService {

    private final TaxFilingRepository taxFilingRepository;
    private final OwnerRepository ownerRepository;
    private final TaxFilingDocumentService filingDocuments;

    /** Filings, newest first, optionally restricted to a model and / or a year. */
    public List<TaxFilingDTO> getFilings(TaxModel model, Integer year) {
        return taxFilingRepository.findAllByOrderByYearDescQuarterDescFiledDateDescIdDesc().stream()
                .filter(f -> model == null || f.getModel() == model)
                .filter(f -> year == null || year.equals(f.getYear()))
                .map(TaxFilingService::toDto)
                .toList();
    }

    public TaxFiling getFilingById(Long id) {
        return taxFilingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tax filing not found with id: " + id));
    }

    public TaxFilingDTO getFilingDtoById(Long id) {
        return toDto(getFilingById(id));
    }

    @Transactional
    public TaxFilingDTO createFiling(TaxFilingRequest request) {
        TaxFiling.TaxFilingBuilder builder = TaxFiling.builder()
                .model(request.getModel())
                .year(request.getYear())
                .filedDate(request.getFiledDate())
                .amount(request.getAmount() == null ? null : request.getAmount().setScale(2, RoundingMode.HALF_UP))
                .description(trimToNull(request.getDescription()))
                .snapshot(request.getSnapshot())
                .notes(trimToNull(request.getNotes()));

        switch (request.getModel()) {
            case MODELO_303 -> {
                if (request.getQuarter() == null) {
                    throw new BadRequestException("The quarter (1-4) is required for a Modelo 303 filing");
                }
                builder.quarter(request.getQuarter());
            }
            case IRPF, MODELO_184 -> {
                if (request.getOwnerId() == null) {
                    throw new BadRequestException(request.getModel() == TaxModel.IRPF
                            ? "The person is required for an IRPF filing"
                            : "The comunidad de bienes is required for a Modelo 184 filing");
                }
                Owner owner = ownerRepository.findById(request.getOwnerId())
                        .orElseThrow(() -> new ResourceNotFoundException("Owner not found with id: " + request.getOwnerId()));
                builder.ownerId(owner.getId()).ownerName(owner.getFullName());
            }
        }
        return toDto(taxFilingRepository.save(builder.build()));
    }

    @Transactional
    public void deleteFiling(Long id) {
        TaxFiling filing = getFilingById(id);
        // Primero sus documentos: la clave ajena no dejaría borrar la declaración
        // y los justificantes se quedarían en el almacén sin dueño.
        filingDocuments.deleteByFiling(id);
        taxFilingRepository.delete(filing);
    }

    private static TaxFilingDTO toDto(TaxFiling f) {
        return TaxFilingDTO.builder()
                .id(f.getId())
                .model(f.getModel())
                .year(f.getYear())
                .quarter(f.getQuarter())
                .ownerId(f.getOwnerId())
                .ownerName(f.getOwnerName())
                .filedDate(f.getFiledDate())
                .amount(f.getAmount())
                .description(f.getDescription())
                .snapshot(f.getSnapshot())
                .notes(f.getNotes())
                .createdAt(f.getCreatedAt())
                .build();
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
