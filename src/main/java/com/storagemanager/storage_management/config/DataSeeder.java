package com.storagemanager.storage_management.config;

import com.storagemanager.storage_management.model.Client;
import com.storagemanager.storage_management.model.Expense;
import com.storagemanager.storage_management.model.Payment;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.StorageGroup;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.model.UnitPriceHistory;
import com.storagemanager.storage_management.model.enums.*;
import com.storagemanager.storage_management.repository.ClientRepository;
import com.storagemanager.storage_management.repository.ExpenseRepository;
import com.storagemanager.storage_management.repository.PaymentRepository;
import com.storagemanager.storage_management.repository.RentalAgreementRepository;
import com.storagemanager.storage_management.repository.StorageGroupRepository;
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
 * - Expenses come from the outgoing side of the same statement (Nov 2023 - Aug 2026):
 *   electricity, AEAT tax payments, IBI / municipal fees, repairs and insurance.
 *   Deposit refunds ("devolución fianza") are NOT expenses and are not seeded.
 * - All 9 units (and every general expense) belong to the storage group
 *   {@value #DEFAULT_GROUP_NAME}. Databases created before groups existed are
 *   migrated at start-up: any unit / general expense without a group is moved
 *   into that group (see {@link #assignUngroupedToDefaultGroup()}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    /** Group holding the original 9 trasteros; created on demand. */
    public static final String DEFAULT_GROUP_NAME = "Pasaxe 29 Baixo dianteiro";
    private static final String DEFAULT_GROUP_DESCRIPTION =
            "Trasteros de la Avenida del Pasaje (A Pasaxe) 29, bajo delantero";

    private final StorageUnitRepository storageUnitRepository;
    private final StorageGroupRepository storageGroupRepository;
    private final ClientRepository clientRepository;
    private final RentalAgreementRepository rentalAgreementRepository;
    private final PaymentRepository paymentRepository;
    private final UnitPriceHistoryRepository unitPriceHistoryRepository;
    private final ExpenseRepository expenseRepository;

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
                if (expenseRepository.count() == 0) {
                    log.info("Backfilling expenses from seed-data.json...");
                    int n = seedExpenses(parseSeedData());
                    log.info("Seeded {} expenses.", n);
                }
                assignUngroupedToDefaultGroup();
                log.info("Database already seeded with {} storage units in {} group(s).",
                        storageUnitRepository.count(), storageGroupRepository.count());
                return;
            }
            log.info("Legacy demo data detected (unit A-101). Replacing it with the real data...");
            expenseRepository.deleteAll();
            unitPriceHistoryRepository.deleteAll();
            paymentRepository.deleteAll();
            rentalAgreementRepository.deleteAll();
            clientRepository.deleteAll();
            storageUnitRepository.deleteAll();
            storageGroupRepository.deleteAll();
        }

        log.info("Seeding database from seed-data.json (real data, BBVA statement mar 2024 - ago 2026)...");

        Map<String, Object> root = parseSeedData();

        // 0. Storage group holding every unit
        StorageGroup defaultGroup = ensureDefaultGroup();

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
                    .storageGroup(defaultGroup)
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

        // 6. Expenses (outgoing side of the bank statement)
        int expenseCount = seedExpenses(root);

        log.info("Seeding complete: {} clients, {} units, {} rentals, {} payments, {} price-history entries, {} expenses.",
                clientsByName.size(), unitsByNumber.size(), rentalsByRef.size(), paymentCount,
                unitPriceHistoryRepository.count(), expenseCount);
    }

    private static Map<String, Object> parseSeedData() throws java.io.IOException {
        String json = new String(new ClassPathResource("seed-data.json").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        return JsonParserFactory.getJsonParser().parseMap(json);
    }

    /** Returns the default storage group, creating it if it does not exist yet. */
    private StorageGroup ensureDefaultGroup() {
        return storageGroupRepository.findByNameIgnoreCase(DEFAULT_GROUP_NAME)
                .orElseGet(() -> {
                    log.info("Creating storage group '{}'...", DEFAULT_GROUP_NAME);
                    return storageGroupRepository.save(StorageGroup.builder()
                            .name(DEFAULT_GROUP_NAME)
                            .description(DEFAULT_GROUP_DESCRIPTION)
                            .build());
                });
    }

    /**
     * One-off migration for databases created before storage groups existed: every
     * unit without a group, and every general expense without a group, is placed in
     * the default group. Idempotent - does nothing once everything is grouped.
     */
    private void assignUngroupedToDefaultGroup() {
        List<StorageUnit> ungroupedUnits = storageUnitRepository.findByStorageGroupIsNull();
        List<Expense> ungroupedExpenses = expenseRepository.findByStorageUnitIsNullAndStorageGroupIsNull();
        if (ungroupedUnits.isEmpty() && ungroupedExpenses.isEmpty()) return;

        StorageGroup group = ensureDefaultGroup();
        for (StorageUnit unit : ungroupedUnits) {
            unit.setStorageGroup(group);
        }
        storageUnitRepository.saveAll(ungroupedUnits);
        for (Expense expense : ungroupedExpenses) {
            expense.setStorageGroup(group);
        }
        expenseRepository.saveAll(ungroupedExpenses);
        log.info("Moved {} storage unit(s) and {} general expense(s) into group '{}'.",
                ungroupedUnits.size(), ungroupedExpenses.size(), group.getName());
    }

    /**
     * Seeds the "expenses" array. An entry may name a unit ("unit": "3") to attribute
     * the cost to that trastero; otherwise it is a general expense of the default group.
     */
    @SuppressWarnings("unchecked")
    private int seedExpenses(Map<String, Object> root) {
        List<Object> entries = (List<Object>) root.get("expenses");
        if (entries == null) return 0;

        Map<String, StorageUnit> unitsByNumber = new HashMap<>();
        for (StorageUnit u : storageUnitRepository.findAll()) {
            unitsByNumber.put(u.getUnitNumber(), u);
        }
        StorageGroup defaultGroup = ensureDefaultGroup();

        int count = 0;
        for (Object o : entries) {
            Map<String, Object> e = (Map<String, Object>) o;
            String unitNumber = str(e, "unit");
            StorageUnit unit = unitNumber == null ? null : unitsByNumber.get(unitNumber);
            expenseRepository.save(Expense.builder()
                    .storageUnit(unit)
                    .storageGroup(unit == null ? defaultGroup : null)
                    .expenseDate(LocalDate.parse(str(e, "date")))
                    .amount(dec(e, "amount"))
                    .description(str(e, "description"))
                    .category(ExpenseCategory.valueOf(str(e, "category")))
                    .build());
            count++;
        }
        return count;
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
