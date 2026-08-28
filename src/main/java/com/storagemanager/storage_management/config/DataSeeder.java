package com.storagemanager.storage_management.config;

import com.storagemanager.storage_management.model.Client;
import com.storagemanager.storage_management.model.Expense;
import com.storagemanager.storage_management.model.Owner;
import com.storagemanager.storage_management.model.Ownership;
import com.storagemanager.storage_management.model.Payment;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.StorageGroup;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.model.UnitPriceHistory;
import com.storagemanager.storage_management.model.enums.*;
import com.storagemanager.storage_management.repository.ClientRepository;
import com.storagemanager.storage_management.repository.ExpenseRepository;
import com.storagemanager.storage_management.repository.OwnerRepository;
import com.storagemanager.storage_management.repository.OwnershipRepository;
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
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Seeds an empty database with the real historical data reconstructed from the
 * BBVA bank statements. The data lives in src/main/resources/seed-data.json:
 * <ul>
 *   <li>the 9 trasteros of group {@value #DEFAULT_GROUP_NAME} (statement of the
 *       storage account, March 2024 - August 2026);</li>
 *   <li>the 2 apartments of group "Pisos Pasaxe 29" (statement of the flats
 *       account, July 2021 - August 2026), which are VAT exempt.</li>
 * </ul>
 * Notes on the reconstruction:
 * - Only incoming bank transfers appear in the statements; months without a
 *   transfer inside a tenancy may have been paid in cash and are NOT seeded.
 * - Deposits ("fianzas") are stored on the rental agreement, not as payments;
 *   deposit refunds coming back from the IGVS are not seeded either.
 * - Client emails/phones are placeholders pending real contact data.
 * - Expenses come from the outgoing side of both statements. Storage account
 *   (Nov 2023 - Aug 2026): electricity, AEAT tax payments, IBI / municipal fees,
 *   repairs and insurance. Flats account (Jul 2024 - Aug 2026, attributed to the
 *   "Pisos Pasaxe 29" group): IBI, electricity, water, the monthly transfers to
 *   the owners and card payments (restaurants and shopping). The monthly 53,40 EUR community transfer
 *   ("XIAO BERNAL TERCEIROS E BAIXOS") is split into 20 EUR per apartment (unit
 *   expenses of 3D and 3E), 6,70 EUR for the trasteros group and 6,70 EUR for the
 *   "Pasaxe 29 Baixo traseiro" group (no units yet). Excluded from the flats
 *   statement: "ABONO NOMINA" transfers, the 2.000 / 2.100 EUR transfers from Bernal Varela
 *   Gomez, any outflow reimbursed by an inflow of the same amount (tenant supply
 *   refunds, card payments repaid by the owners) and the deposit forwarded to the IGVS;
 *   the January 2026 electricity bills, only partly refunded by the tenants, are
 *   seeded for the uncovered 2,10 EUR.
 * <p>
 * On an already-seeded database the seeder is incremental and idempotent: it
 * backfills price history / groups / kinds when missing, loads the expenses of
 * every group that has none yet, and any unit present in seed-data.json but
 * absent from the database is loaded together with its clients, rentals and
 * payments (this is how the apartments reach a database created before they
 * existed). The owners ("owners") and their shares ("ownerships", given as
 * fractions such as "1/6" or as percentages) are loaded when the owners table
 * is still empty.
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
    private final OwnerRepository ownerRepository;
    private final OwnershipRepository ownershipRepository;

    @Override
    public void run(String... args) throws Exception {
        if (storageUnitRepository.count() > 0) {
            boolean legacyDemoData = storageUnitRepository.findAll().stream()
                    .anyMatch(u -> "A-101".equals(u.getUnitNumber()));
            if (!legacyDemoData) {
                Map<String, Object> root = parseSeedData();
                if (unitPriceHistoryRepository.count() == 0) {
                    log.info("Backfilling unit price history from existing rental agreements...");
                    seedPriceHistoryFromRentals(storageUnitRepository.findAll());
                }
                assignUngroupedToDefaultGroup();
                seedMissingUnits(root);
                seedMissingExpenses(root);
                seedMissingOwners(root);
                log.info("Database already seeded with {} units in {} group(s).",
                        storageUnitRepository.count(), storageGroupRepository.count());
                return;
            }
            log.info("Legacy demo data detected (unit A-101). Replacing it with the real data...");
            ownershipRepository.deleteAll();
            ownerRepository.deleteAll();
            expenseRepository.deleteAll();
            unitPriceHistoryRepository.deleteAll();
            paymentRepository.deleteAll();
            rentalAgreementRepository.deleteAll();
            clientRepository.deleteAll();
            storageUnitRepository.deleteAll();
            storageGroupRepository.deleteAll();
        }

        log.info("Seeding database from seed-data.json (real data from the BBVA statements)...");

        Map<String, Object> root = parseSeedData();

        // 0. Storage groups
        Map<String, StorageGroup> groupsByName = seedGroups(root);

        // 1. Clients
        Map<String, Client> clientsByName = seedClients(root, new HashMap<>());

        // 2. Units (trasteros and apartments)
        Map<String, StorageUnit> unitsByNumber = new HashMap<>();
        List<StorageUnit> units = seedUnits(root, groupsByName, unitsByNumber);

        // 3. Rental agreements
        Map<Integer, RentalAgreement> rentalsByRef = seedRentals(root, unitsByNumber, clientsByName, null);

        // 4. Payments (all PAID by bank transfer, from the statements)
        int paymentCount = seedPayments(root, rentalsByRef);

        // 5. Price history, derived from how each unit's rent evolved across tenancies
        seedPriceHistoryFromRentals(units);

        // 6. Expenses (outgoing side of both bank statements)
        int expenseCount = seedExpenses(root, null);

        // 7. Owners and their shares in the groups / units
        Map<String, Owner> ownersByName = seedOwners(root);
        int ownershipCount = seedOwnerships(root, ownersByName, groupsByName, unitsByNumber);

        log.info("Seeding complete: {} groups, {} clients, {} units, {} rentals, {} payments, {} price-history entries, {} expenses, {} owners, {} shares.",
                groupsByName.size(), clientsByName.size(), units.size(), rentalsByRef.size(), paymentCount,
                unitPriceHistoryRepository.count(), expenseCount, ownersByName.size(), ownershipCount);
    }

    private static Map<String, Object> parseSeedData() throws java.io.IOException {
        String json = new String(new ClassPathResource("seed-data.json").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        return JsonParserFactory.getJsonParser().parseMap(json);
    }

    // ------------------------------------------------------------------
    // Building blocks (each one only creates what does not exist yet)
    // ------------------------------------------------------------------

    /** Groups from the "groups" array plus the default one; keyed by name. */
    @SuppressWarnings("unchecked")
    private Map<String, StorageGroup> seedGroups(Map<String, Object> root) {
        Map<String, StorageGroup> byName = new HashMap<>();
        for (StorageGroup g : storageGroupRepository.findAll()) {
            byName.put(g.getName(), g);
        }
        StorageGroup defaultGroup = ensureDefaultGroup();
        byName.put(defaultGroup.getName(), defaultGroup);

        List<Object> entries = (List<Object>) root.get("groups");
        if (entries == null) return byName;
        for (Object o : entries) {
            Map<String, Object> g = (Map<String, Object>) o;
            String name = str(g, "name");
            if (name == null || byName.containsKey(name)) continue;
            StorageGroup group = storageGroupRepository.findByNameIgnoreCase(name)
                    .orElseGet(() -> {
                        log.info("Creating storage group '{}'...", name);
                        return storageGroupRepository.save(StorageGroup.builder()
                                .name(name)
                                .description(str(g, "description"))
                                .build());
                    });
            byName.put(group.getName(), group);
        }
        return byName;
    }

    /** Clients from the "clients" array that are not in {@code existing} (keyed by full name). */
    @SuppressWarnings("unchecked")
    private Map<String, Client> seedClients(Map<String, Object> root, Map<String, Client> existing) {
        Map<String, Client> byName = new HashMap<>(existing);
        for (Object o : (List<Object>) root.get("clients")) {
            Map<String, Object> c = (Map<String, Object>) o;
            String name = str(c, "fullName");
            if (byName.containsKey(name)) continue;
            Client client = clientRepository.save(Client.builder()
                    .fullName(name)
                    .email(str(c, "email"))
                    .phone(str(c, "phone"))
                    .notes(str(c, "notes"))
                    .build());
            byName.put(client.getFullName(), client);
        }
        return byName;
    }

    /**
     * Units from the "units" array that are not yet in {@code unitsByNumber}; the map
     * is completed with the created ones. Returns only the units created by this call.
     */
    @SuppressWarnings("unchecked")
    private List<StorageUnit> seedUnits(Map<String, Object> root, Map<String, StorageGroup> groupsByName,
                                        Map<String, StorageUnit> unitsByNumber) {
        List<StorageUnit> created = new ArrayList<>();
        for (Object o : (List<Object>) root.get("units")) {
            Map<String, Object> u = (Map<String, Object>) o;
            String number = str(u, "unitNumber");
            if (unitsByNumber.containsKey(number)) continue;

            String groupName = str(u, "group");
            StorageGroup group = groupName != null ? groupsByName.get(groupName) : null;
            if (group == null) {
                if (groupName != null) log.warn("Unknown group '{}' for unit {}; using the default group.", groupName, number);
                group = groupsByName.get(DEFAULT_GROUP_NAME);
            }
            String kind = str(u, "kind");

            StorageUnit unit = storageUnitRepository.save(StorageUnit.builder()
                    .unitNumber(number)
                    .name(str(u, "name"))
                    .kind(kind == null ? UnitKind.STORAGE_UNIT : UnitKind.valueOf(kind))
                    .storageGroup(group)
                    .sizeSquareMeters(Double.valueOf(str(u, "sizeSquareMeters")))
                    .location(str(u, "location"))
                    .baseMonthlyRate(dec(u, "baseMonthlyRate"))
                    .status(UnitStatus.valueOf(str(u, "status")))
                    .description(str(u, "description"))
                    .build());
            unitsByNumber.put(unit.getUnitNumber(), unit);
            created.add(unit);
        }
        return created;
    }

    /**
     * Rental agreements from the "rentals" array, keyed by their seed "ref". When
     * {@code onlyUnits} is given, only the rentals of those unit numbers are created.
     */
    @SuppressWarnings("unchecked")
    private Map<Integer, RentalAgreement> seedRentals(Map<String, Object> root, Map<String, StorageUnit> unitsByNumber,
                                                      Map<String, Client> clientsByName, Set<String> onlyUnits) {
        Map<Integer, RentalAgreement> rentalsByRef = new HashMap<>();
        long seq = rentalAgreementRepository.count();
        for (Object o : (List<Object>) root.get("rentals")) {
            Map<String, Object> r = (Map<String, Object>) o;
            String unitNumber = str(r, "unit");
            if (onlyUnits != null && !onlyUnits.contains(unitNumber)) continue;

            StorageUnit unit = unitsByNumber.get(unitNumber);
            Client client = clientsByName.get(str(r, "client"));
            if (unit == null || client == null) {
                log.warn("Skipping rental ref {}: unknown unit '{}' or client '{}'.", r.get("ref"), unitNumber, str(r, "client"));
                continue;
            }
            LocalDate start = LocalDate.parse(str(r, "startDate"));
            boolean active = r.get("endDate") == null;

            String agreementNumber;
            do {
                seq++;
                agreementNumber = String.format("RNT-%d-%03d", start.getYear(), seq);
            } while (rentalAgreementRepository.findByAgreementNumber(agreementNumber).isPresent());

            RentalAgreement rental = rentalAgreementRepository.save(RentalAgreement.builder()
                    .agreementNumber(agreementNumber)
                    .storageUnit(unit)
                    .client(client)
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
        return rentalsByRef;
    }

    /**
     * Payments from the "payments" array whose rentalRef is in {@code rentalsByRef}.
     * "amount" is the rent due; an optional "amountPaid" records a larger transfer.
     */
    @SuppressWarnings("unchecked")
    private int seedPayments(Map<String, Object> root, Map<Integer, RentalAgreement> rentalsByRef) {
        int count = 0;
        for (Object o : (List<Object>) root.get("payments")) {
            Map<String, Object> p = (Map<String, Object>) o;
            RentalAgreement rental = rentalsByRef.get(((Number) p.get("rentalRef")).intValue());
            if (rental == null) continue;
            BigDecimal amount = dec(p, "amount");
            BigDecimal amountPaid = p.get("amountPaid") != null ? dec(p, "amountPaid") : amount;
            paymentRepository.save(Payment.builder()
                    .rentalAgreement(rental)
                    .storageUnit(rental.getStorageUnit())
                    .client(rental.getClient())
                    .billingPeriodYear(((Number) p.get("year")).intValue())
                    .billingPeriodMonth(((Number) p.get("month")).intValue())
                    .amountDue(amount)
                    .amountPaid(amountPaid)
                    .dueDate(LocalDate.parse(str(p, "dueDate")))
                    .paymentDate(LocalDate.parse(str(p, "paymentDate")))
                    .status(PaymentStatus.PAID)
                    .paymentMethod(PaymentMethod.BANK_TRANSFER)
                    .notes(str(p, "notes"))
                    .build());
            count++;
        }
        return count;
    }

    /**
     * Incremental load for an already-seeded database: every unit of seed-data.json
     * missing from the database is created together with its clients, rentals,
     * payments and price history. Existing units are left untouched.
     */
    private void seedMissingUnits(Map<String, Object> root) {
        Map<String, StorageUnit> unitsByNumber = new HashMap<>();
        for (StorageUnit u : storageUnitRepository.findAll()) {
            unitsByNumber.put(u.getUnitNumber(), u);
        }
        Map<String, StorageGroup> groupsByName = seedGroups(root);
        List<StorageUnit> created = seedUnits(root, groupsByName, unitsByNumber);
        if (created.isEmpty()) return;

        Set<String> createdNumbers = new HashSet<>();
        for (StorageUnit u : created) {
            createdNumbers.add(u.getUnitNumber());
        }
        log.info("Loading {} new unit(s) from seed-data.json: {}", created.size(), createdNumbers);

        Map<String, Client> existingClients = new HashMap<>();
        for (Client c : clientRepository.findAll()) {
            existingClients.put(c.getFullName(), c);
        }
        Map<String, Client> clientsByName = seedClients(root, existingClients);
        Map<Integer, RentalAgreement> rentalsByRef = seedRentals(root, unitsByNumber, clientsByName, createdNumbers);
        int payments = seedPayments(root, rentalsByRef);
        seedPriceHistoryFromRentals(created);

        log.info("Loaded {} rental(s) and {} payment(s) for the new unit(s).", rentalsByRef.size(), payments);
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
     * One-off migration for databases created before storage groups / unit kinds
     * existed: units without a kind become storage units; every unit without a
     * group, and every general expense without a group, is placed in the default
     * group. Idempotent - does nothing once everything is grouped.
     */
    private void assignUngroupedToDefaultGroup() {
        // Rows created before unit kinds existed are storage units
        List<StorageUnit> unkindUnits = storageUnitRepository.findByKindIsNull();
        if (!unkindUnits.isEmpty()) {
            for (StorageUnit unit : unkindUnits) {
                unit.setKind(UnitKind.STORAGE_UNIT);
            }
            storageUnitRepository.saveAll(unkindUnits);
            log.info("Marked {} unit(s) without a kind as STORAGE_UNIT.", unkindUnits.size());
        }

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
     * the cost to that unit, or a group ("group": "Pisos Pasaxe 29") for a general
     * expense of that group; otherwise it is a general expense of the default group.
     * When {@code onlyGroups} is given, only the entries attributed to those group
     * names are created.
     */
    @SuppressWarnings("unchecked")
    private int seedExpenses(Map<String, Object> root, Set<String> onlyGroups) {
        List<Object> entries = (List<Object>) root.get("expenses");
        if (entries == null) return 0;

        Map<String, StorageUnit> unitsByNumber = new HashMap<>();
        for (StorageUnit u : storageUnitRepository.findAll()) {
            unitsByNumber.put(u.getUnitNumber(), u);
        }
        Map<String, StorageGroup> groupsByName = seedGroups(root);
        StorageGroup defaultGroup = groupsByName.get(DEFAULT_GROUP_NAME);

        int count = 0;
        for (Object o : entries) {
            Map<String, Object> e = (Map<String, Object>) o;
            String unitNumber = str(e, "unit");
            StorageUnit unit = unitNumber == null ? null : unitsByNumber.get(unitNumber);
            StorageGroup group = null;
            if (unit == null) {
                String groupName = str(e, "group");
                group = groupName == null ? null : groupsByName.get(groupName);
                if (group == null) {
                    if (groupName != null) log.warn("Unknown group '{}' for expense '{}'; using the default group.", groupName, str(e, "description"));
                    group = defaultGroup;
                }
            }
            String targetGroup = unit != null ? unit.getStorageGroup().getName() : group.getName();
            if (onlyGroups != null && !onlyGroups.contains(targetGroup)) continue;

            expenseRepository.save(Expense.builder()
                    .storageUnit(unit)
                    .storageGroup(group)
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
     * Incremental load for an already-seeded database: the expenses of every group
     * that has no expense yet (neither general nor tied to one of its units) are
     * created from seed-data.json. Groups that already have expenses are left untouched.
     */
    private void seedMissingExpenses(Map<String, Object> root) {
        Set<String> groupsWithoutExpenses = new HashSet<>();
        for (StorageGroup g : storageGroupRepository.findAll()) {
            if (expenseRepository.countByStorageGroupIdOrStorageUnitStorageGroupId(g.getId(), g.getId()) == 0) {
                groupsWithoutExpenses.add(g.getName());
            }
        }
        if (groupsWithoutExpenses.isEmpty()) return;
        int n = seedExpenses(root, groupsWithoutExpenses);
        if (n > 0) log.info("Backfilled {} expense(s) for group(s) without expenses: {}", n, groupsWithoutExpenses);
    }

    /** Owners from the "owners" array that do not exist yet; keyed by full name. */
    @SuppressWarnings("unchecked")
    private Map<String, Owner> seedOwners(Map<String, Object> root) {
        Map<String, Owner> byName = new HashMap<>();
        for (Owner o : ownerRepository.findAll()) {
            byName.put(o.getFullName(), o);
        }
        List<Object> entries = (List<Object>) root.get("owners");
        if (entries == null) return byName;
        for (Object entry : entries) {
            Map<String, Object> o = (Map<String, Object>) entry;
            String name = str(o, "fullName");
            if (name == null || byName.containsKey(name)) continue;
            Owner owner = ownerRepository.findByFullNameIgnoreCase(name)
                    .orElseGet(() -> ownerRepository.save(Owner.builder()
                            .fullName(name)
                            .documentId(str(o, "documentId"))
                            .email(str(o, "email"))
                            .phone(str(o, "phone"))
                            .bankAccount(str(o, "bankAccount"))
                            .notes(str(o, "notes"))
                            .build()));
            byName.put(owner.getFullName(), owner);
        }
        return byName;
    }

    /**
     * Shares from the "ownerships" array: each entry names an "owner" and either a
     * "group" or a "unit", plus a "share" given as a fraction ("1/6") or a percentage
     * (16.6667). Entries whose owner already holds a share of that target are skipped.
     */
    @SuppressWarnings("unchecked")
    private int seedOwnerships(Map<String, Object> root, Map<String, Owner> ownersByName,
                               Map<String, StorageGroup> groupsByName, Map<String, StorageUnit> unitsByNumber) {
        List<Object> entries = (List<Object>) root.get("ownerships");
        if (entries == null) return 0;
        int count = 0;
        for (Object entry : entries) {
            Map<String, Object> s = (Map<String, Object>) entry;
            Owner owner = ownersByName.get(str(s, "owner"));
            String groupName = str(s, "group");
            String unitNumber = str(s, "unit");
            StorageGroup group = groupName != null ? groupsByName.get(groupName) : null;
            StorageUnit unit = unitNumber != null ? unitsByNumber.get(unitNumber) : null;
            if (owner == null || (group == null) == (unit == null)) {
                log.warn("Skipping share of '{}' in group '{}' / unit '{}': unknown owner or target.",
                        str(s, "owner"), groupName, unitNumber);
                continue;
            }
            boolean exists = unit != null
                    ? ownershipRepository.findByOwnerIdAndStorageUnitId(owner.getId(), unit.getId()).isPresent()
                    : ownershipRepository.findByOwnerIdAndStorageGroupId(owner.getId(), group.getId()).isPresent();
            if (exists) continue;
            ownershipRepository.save(Ownership.builder()
                    .owner(owner)
                    .storageGroup(group)
                    .storageUnit(unit)
                    .sharePercent(parseShare(s.get("share")))
                    .notes(str(s, "notes"))
                    .build());
            count++;
        }
        return count;
    }

    /** "1/6" -> 16.6667; a plain number is taken as a percentage. */
    public static BigDecimal parseShare(Object raw) {
        String text = String.valueOf(raw).trim();
        int slash = text.indexOf('/');
        if (slash > 0) {
            BigDecimal numerator = new BigDecimal(text.substring(0, slash).trim());
            BigDecimal denominator = new BigDecimal(text.substring(slash + 1).trim());
            return numerator.multiply(BigDecimal.valueOf(100)).divide(denominator, 4, RoundingMode.HALF_UP);
        }
        return new BigDecimal(text.replace("%", "").trim()).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Incremental load for an already-seeded database: when no owner exists yet,
     * the owners and shares of seed-data.json are created.
     */
    private void seedMissingOwners(Map<String, Object> root) {
        if (ownerRepository.count() > 0) return;
        Map<String, StorageGroup> groupsByName = new HashMap<>();
        for (StorageGroup g : storageGroupRepository.findAll()) {
            groupsByName.put(g.getName(), g);
        }
        Map<String, StorageUnit> unitsByNumber = new HashMap<>();
        for (StorageUnit u : storageUnitRepository.findAll()) {
            unitsByNumber.put(u.getUnitNumber(), u);
        }
        Map<String, Owner> owners = seedOwners(root);
        int shares = seedOwnerships(root, owners, groupsByName, unitsByNumber);
        if (!owners.isEmpty()) log.info("Backfilled {} owner(s) and {} share(s) from seed-data.json.", owners.size(), shares);
    }

    /**
     * Rebuilds the price history of the given units from their rental agreements:
     * one entry per change of rent, in chronological order of agreement start date.
     */
    private void seedPriceHistoryFromRentals(List<StorageUnit> units) {
        for (StorageUnit unit : units) {
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
