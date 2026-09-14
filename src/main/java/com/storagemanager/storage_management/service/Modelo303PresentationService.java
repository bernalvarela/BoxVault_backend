package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.dto.Modelo303DTO;
import com.storagemanager.storage_management.dto.Modelo303PresentationRequest;
import com.storagemanager.storage_management.dto.TaxFilingDTO;
import com.storagemanager.storage_management.dto.TaxFilingDocumentDTO;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.model.TaxFiling;
import com.storagemanager.storage_management.model.enums.DocumentType;
import com.storagemanager.storage_management.model.enums.TaxModel;
import com.storagemanager.storage_management.repository.TaxFilingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * Presentar el Modelo 303 de un trimestre, que son tres cosas a la vez y por eso
 * van juntas: se genera el fichero para la Sede electrónica, se registra la
 * declaración con las cifras congeladas y el fichero queda archivado en ella.
 * <p>
 * El orden importa: primero se genera el fichero —ahí es donde salen los errores
 * de verdad, un NIF que falta o una cuenta que hace falta para domiciliar—, y
 * sólo si sale bien se registra nada. Si archivar el fichero fallara, se cae
 * todo: una declaración registrada sin el fichero que se presentó es peor que no
 * tener nada, porque parece completa.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Modelo303PresentationService {

    /** El .303 es texto de posiciones fijas en ISO-8859-1 (ver Modelo303FileService). */
    private static final String FILE_CONTENT_TYPE = "text/plain";

    private final Modelo303FileService fileService;
    private final TaxService taxService;
    private final TaxFilingRepository taxFilingRepository;
    private final TaxFilingDocumentService filingDocuments;
    private final TaxFilingService taxFilings;
    private final ObjectMapper objectMapper;

    /** Lo presentado: la declaración registrada y el fichero que hay que subir a la Sede. */
    public record Presentation(TaxFilingDTO filing, TaxFilingDocumentDTO file) {}

    @Transactional
    public Presentation present(Modelo303PresentationRequest request) {
        int year = request.getYear();
        int quarter = request.getQuarter();
        taxFilingRepository.findAll().stream()
                .filter(f -> f.getModel() == TaxModel.MODELO_303
                        && Integer.valueOf(year).equals(f.getYear())
                        && Integer.valueOf(quarter).equals(f.getQuarter()))
                .findAny()
                .ifPresent(f -> {
                    throw new BadRequestException("El " + quarter + "T " + year + " ya consta presentado el "
                            + f.getFiledDate() + ". Para corregirlo, genera una autoliquidación rectificativa.");
                });

        Modelo303FileService.Options options = new Modelo303FileService.Options(
                request.getBasis() != null ? request.getBasis() : Modelo303FileService.Basis.COLLECTED,
                money(request.getPendingToOffset()),
                money(request.getOffsetApplied()),
                request.isDirectDebit(),
                null);
        Modelo303FileService.Modelo303File file =
                fileService.generate(year, quarter, request.getOwnerId(), options);

        Modelo303DTO report = taxService.modelo303(year, file.declarantId());
        String label = quarter + "T " + year;
        TaxFiling filing = taxFilingRepository.save(TaxFiling.builder()
                .model(TaxModel.MODELO_303)
                .year(year)
                .quarter(quarter)
                .filedDate(request.getFiledDate() != null ? request.getFiledDate() : LocalDate.now())
                .amount(file.result())
                .description("IVA " + label + ": base " + file.base() + " EUR · devengado " + file.vat()
                        + " EUR · soportado " + file.deductibleVat() + " EUR · resultado " + file.result() + " EUR")
                .snapshot(toJson(report))
                .notes(request.getNotes())
                .build());

        TaxFilingDocumentDTO attached = filingDocuments.attach(filing.getId(), file.fileName(), FILE_CONTENT_TYPE,
                file.content().getBytes(StandardCharsets.ISO_8859_1), DocumentType.OTRO,
                "Fichero generado para importar en la Sede electrónica");

        log.info("Modelo 303 {} presentado por {}: resultado {} EUR, fichero {}",
                label, file.declarantName(), file.result(), file.fileName());
        return new Presentation(taxFilings.getFilingDtoById(filing.getId()), attached);
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2, RoundingMode.HALF_UP);
    }

    private String toJson(Modelo303DTO report) {
        try {
            return objectMapper.writeValueAsString(report);
        } catch (tools.jackson.core.JacksonException e) {
            log.warn("No se pudo serializar el informe del 303: {}", e.getMessage());
            return null;
        }
    }
}
