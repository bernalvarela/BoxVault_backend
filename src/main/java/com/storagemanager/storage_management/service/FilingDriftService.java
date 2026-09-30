package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.dto.FilingDriftDTO;
import com.storagemanager.storage_management.dto.IrpfReportDTO;
import com.storagemanager.storage_management.dto.Modelo184DTO;
import com.storagemanager.storage_management.dto.Modelo303DTO;
import com.storagemanager.storage_management.model.TaxFiling;
import com.storagemanager.storage_management.repository.TaxFilingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Vigila lo ya declarado: recalcula cada declaración presentada y la compara con
 * las cifras que se guardaron al presentarla.
 * <p>
 * Las pruebas automáticas protegen el código con datos de ejemplo; esto protege
 * los datos de verdad. Si alguien corrige un cobro o un gasto de un trimestre ya
 * declarado, el 303 que calcula hoy la aplicación deja de coincidir con el que
 * se presentó, y hasta ahora nadie se enteraba. Puede ser una corrección
 * legítima -y entonces toca una complementaria o una rectificativa- o un error
 * al tocar el periodo que no era; en los dos casos hay que saberlo.
 * <p>
 * Cada declaración guarda en {@code snapshot} el informe completo tal como se
 * presentó. Se leen sólo las cifras que importan de cada modelo, no el informe
 * entero: las líneas de detalle cambian de forma entre versiones de la
 * aplicación, y compararlas daría avisos que no significan nada.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FilingDriftService {

    /** Por debajo de un céntimo es redondeo, no un cambio. */
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    private final TaxFilingRepository filings;
    private final TaxService taxService;
    private final ObjectMapper objectMapper;

    /** Las declaraciones presentadas que ya no cuadran, la más reciente primero. */
    public List<FilingDriftDTO> drifts() {
        // Un mismo informe sirve para varias declaraciones (el IRPF de un año es
        // uno para todos los propietarios): se calcula una vez por llamada.
        Map<String, Object> cache = new HashMap<>();
        List<FilingDriftDTO> out = new ArrayList<>();
        for (TaxFiling filing : filings.findAllByOrderByYearDescQuarterDescFiledDateDescIdDesc()) {
            if (filing.getSnapshot() == null || filing.getSnapshot().isBlank()) continue;
            try {
                List<FilingDriftDTO.Change> changes = switch (filing.getModel()) {
                    case MODELO_303 -> compare303(filing, cache);
                    case MODELO_184 -> compare184(filing, cache);
                    case IRPF -> compareIrpf(filing, cache);
                };
                if (!changes.isEmpty()) {
                    out.add(new FilingDriftDTO(filing.getId(), filing.getModel(), filing.getYear(),
                            filing.getQuarter(), labelOf(filing), filing.getFiledDate(), changes));
                }
            } catch (RuntimeException e) {
                // Una copia ilegible (de una versión muy antigua) no puede tumbar la
                // comprobación de las demás.
                log.warn("No se pudo comparar la declaración {} ({} {}): {}",
                        filing.getId(), filing.getModel(), filing.getYear(), e.getMessage());
            }
        }
        return out;
    }

    // --- Modelo 303: las cifras del trimestre presentado ---------------------

    private List<FilingDriftDTO.Change> compare303(TaxFiling filing, Map<String, Object> cache) {
        Modelo303DTO filed = objectMapper.readValue(filing.getSnapshot(), Modelo303DTO.class);
        Integer quarter = filing.getQuarter();
        if (quarter == null) return List.of();
        Long ownerId = filed.getOwnerId();
        Modelo303DTO now = (Modelo303DTO) cache.computeIfAbsent("303|" + filing.getYear() + "|" + ownerId,
                k -> taxService.modelo303(filing.getYear(), ownerId));

        Modelo303DTO.Quarter before = quarterOf(filed, quarter);
        Modelo303DTO.Quarter after = quarterOf(now, quarter);
        if (before == null || after == null) return List.of();

        List<FilingDriftDTO.Change> changes = new ArrayList<>();
        add(changes, "Base cobrada", before.getCollectedBase(), after.getCollectedBase());
        add(changes, "IVA repercutido (cobrado)", before.getCollectedVat(), after.getCollectedVat());
        add(changes, "Base devengada", before.getExpectedBase(), after.getExpectedBase());
        add(changes, "IVA repercutido (devengado)", before.getExpectedVat(), after.getExpectedVat());
        add(changes, "Base de gastos deducibles", before.getDeductibleBase(), after.getDeductibleBase());
        add(changes, "IVA soportado deducible", before.getDeductibleVat(), after.getDeductibleVat());
        return changes;
    }

    private static Modelo303DTO.Quarter quarterOf(Modelo303DTO report, int quarter) {
        if (report == null || report.getQuarters() == null) return null;
        return report.getQuarters().stream().filter(q -> q.getQuarter() == quarter).findFirst().orElse(null);
    }

    // --- Modelo 184: lo que la comunidad de bienes atribuye -----------------

    private List<FilingDriftDTO.Change> compare184(TaxFiling filing, Map<String, Object> cache) {
        Modelo184DTO filed = objectMapper.readValue(filing.getSnapshot(), Modelo184DTO.class);
        Long entityId = filing.getOwnerId() != null ? filing.getOwnerId() : filed.getEntityId();
        if (entityId == null) return List.of();
        Modelo184DTO now = (Modelo184DTO) cache.computeIfAbsent("184|" + filing.getYear() + "|" + entityId,
                k -> taxService.modelo184(filing.getYear(), entityId));

        List<FilingDriftDTO.Change> changes = new ArrayList<>();
        add(changes, "Ingresos (base)", filed.getIncomeBase(), now.getIncomeBase());
        add(changes, "Gastos", filed.getExpenses(), now.getExpenses());
        add(changes, "Base atribuida", filed.getAttributedBase(), now.getAttributedBase());
        if (filed.getMembers() != null && now.getMembers() != null) {
            for (Modelo184DTO.Member before : filed.getMembers()) {
                now.getMembers().stream()
                        .filter(m -> Objects.equals(m.getOwnerId(), before.getOwnerId()))
                        .findFirst()
                        .ifPresent(after -> add(changes, "Rendimiento de " + before.getOwnerName(),
                                before.getNet(), after.getNet()));
            }
        }
        return changes;
    }

    // --- IRPF: lo de cada propietario ---------------------------------------

    private List<FilingDriftDTO.Change> compareIrpf(TaxFiling filing, Map<String, Object> cache) {
        if (filing.getOwnerId() == null) return List.of();
        IrpfReportDTO filed = objectMapper.readValue(filing.getSnapshot(), IrpfReportDTO.class);
        IrpfReportDTO now = (IrpfReportDTO) cache.computeIfAbsent("irpf|" + filing.getYear(),
                k -> taxService.irpf(filing.getYear()));

        IrpfReportDTO.OwnerReport before = ownerOf(filed, filing.getOwnerId());
        IrpfReportDTO.OwnerReport after = ownerOf(now, filing.getOwnerId());
        List<FilingDriftDTO.Change> changes = new ArrayList<>();
        if (before == null) return changes;
        if (after == null) {
            // Presentó renta y hoy no le sale ninguna: eso también es un cambio.
            add(changes, "Rendimiento neto total", before.getTotalNet(), BigDecimal.ZERO);
            return changes;
        }
        if (before.getRental() != null && after.getRental() != null) {
            add(changes, "Ingresos por alquiler", before.getRental().getIncomeBase(), after.getRental().getIncomeBase());
            add(changes, "Gastos deducibles", before.getRental().getExpenses(), after.getRental().getExpenses());
        }
        if (before.getAttribution() != null && after.getAttribution() != null) {
            add(changes, "Rendimiento atribuido", before.getAttribution().getNet(), after.getAttribution().getNet());
        }
        add(changes, "Rendimiento neto total", before.getTotalNet(), after.getTotalNet());
        return changes;
    }

    private static IrpfReportDTO.OwnerReport ownerOf(IrpfReportDTO report, Long ownerId) {
        if (report == null || report.getOwners() == null) return null;
        return report.getOwners().stream()
                .filter(o -> Objects.equals(o.getOwnerId(), ownerId))
                .findFirst().orElse(null);
    }

    // ------------------------------------------------------------------------

    private static void add(List<FilingDriftDTO.Change> changes, String field, BigDecimal filed, BigDecimal current) {
        BigDecimal before = filed == null ? BigDecimal.ZERO : filed;
        BigDecimal after = current == null ? BigDecimal.ZERO : current;
        BigDecimal difference = after.subtract(before);
        if (difference.abs().compareTo(TOLERANCE) >= 0) {
            changes.add(new FilingDriftDTO.Change(field, before, after, difference));
        }
    }

    private static String labelOf(TaxFiling filing) {
        return switch (filing.getModel()) {
            case MODELO_303 -> "Modelo 303 · " + filing.getQuarter() + "T " + filing.getYear();
            case MODELO_184 -> "Modelo 184 · " + filing.getYear()
                    + (filing.getOwnerName() != null ? " · " + filing.getOwnerName() : "");
            case IRPF -> "IRPF " + filing.getYear()
                    + (filing.getOwnerName() != null ? " · " + filing.getOwnerName() : "");
        };
    }
}
