package com.storagemanager.storage_management.config;

import com.storagemanager.storage_management.model.Client;
import com.storagemanager.storage_management.model.Payment;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.model.UnitPriceHistory;
import com.storagemanager.storage_management.model.enums.*;
import com.storagemanager.storage_management.repository.ClientRepository;
import com.storagemanager.storage_management.repository.PaymentRepository;
import com.storagemanager.storage_management.repository.RentalAgreementRepository;
import com.storagemanager.storage_management.repository.StorageUnitRepository;
import com.storagemanager.storage_management.repository.UnitPriceHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Seeds an empty database with the real historical data of the 9 trasteros
 * (Avenida del Pasaje), reconstructed from the BBVA bank statement covering
 * March 2024 - August 2026. The data lives in src/main/resources/seed-data.json.
 *
 * Notes on the reconstruction:
 * - Only incoming bank transfers appear in the statement; months without a
 *   transfer inside a tenancy may have been paid in cash and are NOT seeded.
 * - Deposits ("fianzas") are stored on the rental agreement, not as payments.
 * - Client emails/phones are placeholders pending real contact data.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final StorageUnitRepository storageUnitRepository;
    private final ClientRepository clientRepository;
    private final RentalAgreementRepository rentalAgreementRepository;
    private final PaymentRepository paymentRepository;
    private final UnitPriceHistoryRepository unitPriceHistoryRepository;

    @Override
    @SuppressWarnings("unchecked")
    public void run(String... args) throws Exception {
        if (storageUnitRepository.count() > 0) {
            boolean legacyDemoData = storageUnitRepository.findAll().stream()
                    .anyMatch(u -> "A-101".equals(u.getUnitNumber()));
            if (!legacyDemoData) {
                if (unitPriceHistoryRepository.count() == 0) {
                    log.info("Backfilling unit price history from existing rental agreements...");
                    seedPriceHistoryFromRentals();
                }
                log.info("Database already seeded with {} storage units.", storageUnitRepository.count());
                return;
            }
            log.info("Legacy demo data detected (unit A-101). Replacing it with the real data...");
            unitPriceHistoryRepository.deleteAll();
            paymentRepository.deleteAll();
            rentalAgreementRepository.deleteAll();
            clientRepository.deleteAll();
            storageUnitRepository.deleteAll();
        }

        log.info("Seeding database from seed-data.json (real data, BBVA statement mar 2024 - ago 2026)...");

        String json = new String(new ClassPathResource("seed-data.json").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        Map<String, Object> root = JsonParserFactory.getJsonParser().parseMap(json);

        // 1. Clients
        Map<String, Client> clientsByName = new HashMap<>();
        for (Object o : (List<Object>) root.get("clients")) {
            Map<String, Object> c = (Map<String, Object>) o;
            Client client = clientRepository.save(Client.builder()
                    .fullName(str(c, "fullName"))
                    .email(str(c, "email"))
                    .phone(str(c, "phone"))
                    .notes(str(c, "notes"))
                    .build());
            clientsByName.put(client.getFullName(), client);
        }

        // 2. Storage units
        Map<String, StorageUnit> unitsByNumber = new HashMap<>();
        for (Object o : (List<Object>) root.get("units")) {
            Map<String, Object> u = (Map<String, Object>) o;
            StorageUnit unit = storageUnitRepository.save(StorageUnit.builder()
                    .unitNumber(str(u, "unitNumber"))
                    .name(str(u, "name"))
                    .sizeSquareMeters(Double.valueOf(str(u, "sizeSquareMeters")))
                    .location(str(u, "location"))
                    .baseMonthlyRate(dec(u, "baseMonthlyRate"))
                    .status(UnitStatus.valueOf(str(u, "status")))
                    .description(str(u, "description"))
                    .build());
            unitsByNumber.put(unit.getUnitNumber(), unit);
        }

        // 3. Rental agreements
        Map<Integer, RentalAgreement> rentalsByRef = new HashMap<>();
        int seq = 0;
        for (Object o : (List<Object>) root.get("rentals")) {
            Map<String, Object> r = (Map<String, Object>) o;
            seq++;
            LocalDate start = LocalDate.parse(str(r, "startDate"));
            boolean active = r.get("endDate") == null;
            RentalAgreement rental = rentalAgreementRepository.save(RentalAgreement.builder()
                    .agreementNumber(String.format("RNT-%d-%03d", start.getYear(), seq))
                    .storageUnit(unitsByNumber.get(str(r, "unit")))
                    .client(clientsByName.get(str(r, "client")))
                    .startDate(start)
                    .endDate(active ? null : LocalDate.parse(str(r, "endDate")))
                    .billingDayOfMonth(1)
                    .monthlyRent(dec(r, "monthlyRent"))
                    .securityDeposit(dec(r, "securityDeposit"))
                    .depositPaid(Boolean.TRUE.equals(r.get("depositPaid")))
                    .status(RentalStatus.valueOf(str(r, "status")))
                    .autoRenew(active)
                    .notes(str(r, "notes"))
                    .build());
            rentalsByRef.put(((Number) r.get("ref")).intValue(), rental);
        }

        // 4. Payments (all PAID by bank transfer, from the statement)
        int paymentCount = 0;
        for (Object o : (List<Object>) root.get("payments")) {
            Map<String, Object> p = (Map<String, Object>) o;
            RentalAgreement rental = rentalsByRef.get(((Number) p.get("rentalRef")).intValue());
            BigDecimal amount = dec(p, "amount");
            paymentRepository.save(Payment.builder()
                    .rentalAgreement(rental)
                    .storageUnit(rental.getStorageUnit())
                    .client(rental.getClient())
                    .billingPeriodYear(((Number) p.get("year")).intValue())
                    .billingPeriodMonth(((Number) p.get("month")).intValue())
                    .amountDue(amount)
                    .amountPaid(amount)
                    .dueDate(LocalDate.parse(str(p, "dueDate")))
                    .paymentDate(LocalDate.parse(str(p, "paymentDate")))
                    .status(PaymentStatus.PAID)
                    .paymentMethod(PaymentMethod.BANK_TRANSFER)
                    .notes(str(p, "notes"))
                    .build());
            paymentCount++;
        }

        // 5. Price history, derived from how each unit's rent evolved across tenancies
        seedPriceHistoryFromRentals();

        log.info("Seeding complete: {} clients, {} units, {} rentals, {} payments, {} price-history entries.",
                clientsByName.size(), unitsByNumber.size(), rentalsByRef.size(), paymentCount,
                unitPriceHistoryRepository.count());
    }

    /**
     * Rebuilds each unit's price history from its rental agreements: one entry
     * per change of rent, in chronological order of agreement start date.
     */
    private void seedPriceHistoryFromRentals() {
        for (StorageUnit unit : storageUnitRepository.findAll()) {
            BigDecimal previous = null;
            List<RentalAgreement> agreements = rentalAgreementRepository.findByStorageUnitId(unit.getId())
                    .stream()
                    .sorted(java.util.Comparator.comparing(RentalAgreement::getStartDate))
                    .toList();
            for (RentalAgreement r : agreements) {
                if (previous == null || previous.compareTo(r.getMonthlyRent()) != 0) {
                    unitPriceHistoryRepository.save(UnitPriceHistory.builder()
                            .storageUnit(unit)
                            .monthlyPrice(r.getMonthlyRent())
                            .effectiveFrom(r.getStartDate())
                            .notes(previous == null ? "Precio inicial" : "Cambio de precio")
                            .build());
                    previous = r.getMonthlyRent();
                }
            }
        }
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static BigDecimal dec(Map<String, Object> map, String key) {
        return new BigDecimal(str(map, key));
    }
}
