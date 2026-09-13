package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.dto.Modelo303DTO;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Register of filed tax returns (see {@link TaxFiling}). */
@Service
@RequiredArgsConstructor
public class TaxFilingService {

    private final TaxFilingRepository taxFilingRepository;
    private final OwnerRepository ownerRepository;
    private final TaxFilingDocumentService filingDocuments;
    private final TaxService taxService;

    /** Filings, newest first, optionally restricted to a model and / or a year. */
    public List<TaxFilingDTO> getFilings(TaxModel model, Integer year) {
        Map<String, Modelo303DTO> reports = new HashMap<>();
        return taxFilingRepository.findAllByOrderByYearDescQuarterDescFiledDateDescIdDesc().stream()
                .filter(f -> model == null || f.getModel() == model)
                .filter(f -> year == null || year.equals(f.getYear()))
                .map(f -> toDto(f, reports))
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

    private TaxFilingDTO toDto(TaxFiling f) {
        return toDto(f, new HashMap<>());
    }

    /**
     * La declaración con lo que la aplicación calcula hoy para ese periodo al lado,
     * para poder ver si lo ingresado sigue cuadrando. {@code reports} cachea un
     * informe por año y ámbito, que es lo caro de esta cuenta.
     */
    private TaxFilingDTO toDto(TaxFiling f, Map<String, Modelo303DTO> reports) {
        BigDecimal computed = computedAmount(f, reports);
        BigDecimal difference = computed == null || f.getAmount() == null
                ? null
                : f.getAmount().subtract(computed);
        return TaxFilingDTO.builder()
                .expenseId(f.getExpenseId())
                .computedAmount(computed)
                .difference(difference)
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

    /**
     * Lo que habría que haber ingresado en el trimestre de una declaración del
     * Modelo 303 según los datos de hoy: el IVA devengado menos el soportado. Los
     * demás modelos no se recalculan: su cifra depende de repartos y gastos del
     * ejercicio entero y no hay un "lo pagado" que comparar.
     */
    private BigDecimal computedAmount(TaxFiling f, Map<String, Modelo303DTO> reports) {
        if (f.getModel() != TaxModel.MODELO_303 || f.getQuarter() == null || f.getYear() == null) return null;
        Modelo303DTO report = reports.computeIfAbsent(f.getYear() + "|" + f.getOwnerId(),
                key -> taxService.modelo303(f.getYear(), f.getOwnerId()));
        Modelo303DTO.Quarter q = report.getQuarters().get(f.getQuarter() - 1);
        return q.getResultVat() != null ? q.getResultVat() : q.getCollectedVat();
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
