package com.storagemanager.storage_management.config;

import com.storagemanager.storage_management.dto.IrpfReportDTO;
import com.storagemanager.storage_management.dto.Modelo184DTO;
import com.storagemanager.storage_management.dto.Modelo303DTO;
import com.storagemanager.storage_management.model.Client;
import com.storagemanager.storage_management.model.Expense;
import com.storagemanager.storage_management.model.Owner;
import com.storagemanager.storage_management.model.OwnerMembership;
import com.storagemanager.storage_management.model.Ownership;
import com.storagemanager.storage_management.model.Payment;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.model.TaxFiling;
import com.storagemanager.storage_management.model.UnitPriceHistory;
import com.storagemanager.storage_management.model.enums.*;
import com.storagemanager.storage_management.repository.ClientRepository;
import com.storagemanager.storage_management.repository.ExpenseRepository;
import com.storagemanager.storage_management.repository.OwnerMembershipRepository;
import com.storagemanager.storage_management.repository.OwnerRepository;
import com.storagemanager.storage_management.repository.OwnershipRepository;
import com.storagemanager.storage_management.repository.PaymentRepository;
import com.storagemanager.storage_management.repository.RentalAgreementRepository;
import com.storagemanager.storage_management.repository.StorageUnitRepository;
import com.storagemanager.storage_management.repository.TaxFilingRepository;
import com.storagemanager.storage_management.repository.UnitPriceHistoryRepository;
import com.storagemanager.storage_management.service.TaxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

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
 *   <li>the two locales of Pasaxe 29 - "Bajo delantero" (BD), the storage business
 *       with the 9 trasteros inside it, and the empty "Bajo trasero" (BT) - both
 *       owned by the Comunidad de bienes Pasaxe 29;</li>
 *   <li>the 9 trasteros (statement of the storage account, March 2024 - August 2026);</li>
 *   <li>the 2 apartments 3D / 3E (statement of the flats account, July 2021 -
 *       August 2026), VAT exempt and held directly by the persons.</li>
 * </ul>
 * Notes on the reconstruction:
 * - Only incoming bank transfers appear in the statements; months without a
 *   transfer inside a tenancy may have been paid in cash and are NOT seeded.
 * - Deposits ("fianzas") are stored on the rental agreement, not as payments;
 *   deposit refunds coming back from the IGVS are not seeded either.
 * - Client emails/phones are placeholders pending real contact data.
 * - Expenses come from the outgoing side of both statements. Storage account
 *   (Nov 2023 - Aug 2026, attributed to the BD local): electricity, AEAT tax
 *   payments, IBI / municipal fees, repairs and insurance. Flats account (Jul 2024 -
 *   Aug 2026): IBI, electricity, water, the monthly transfers to the owners and
 *   card payments; the entries that belong to both flats ("units": ["3D", "3E"])
 *   are split evenly between them. The monthly 53,40 EUR community transfer
 *   ("XIAO BERNAL TERCEIROS E BAIXOS") is split into 20 EUR per apartment, 6,70 EUR
 *   for BD and 6,70 EUR for BT. Excluded from the flats statement: "ABONO NOMINA"
 *   transfers, the 2.000 / 2.100 EUR transfers from Bernal Varela Gomez, any
 *   outflow reimbursed by an inflow of the same amount and the deposit forwarded to
 *   the IGVS; the January 2026 electricity bills, only partly refunded by the
 *   tenants, are seeded for the uncovered 2,10 EUR.
 * <p>
 * On an already-seeded database the seeder is incremental and idempotent: it
 * backfills price history / kinds when missing, any unit present in seed-data.json
 * but absent from the database is loaded together with its clients, rentals and
 * payments, expenses / owners are loaded when their tables are still empty, and the
 * filed tax returns are registered when missing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    /** Unit number of the local holding the trasteros. */
    public static final String STORAGE_PREMISES_NUMBER = "BD";
    /** Name of the seeded comunidad de bienes. */
    public static final String ENTITY_NAME = "Comunidad de bienes Pasaxe 29";

    private final StorageUnitRepository storageUnitRepository;
    private final ClientRepository clientRepository;
    private final RentalAgreementRepository rentalAgreementRepository;
    private final PaymentRepository paymentRepository;
    private final UnitPriceHistoryRepository unitPriceHistoryRepository;
    private final ExpenseRepository expenseRepository;
    private final OwnerRepository ownerRepository;
    private final OwnershipRepository ownershipRepository;
    private final OwnerMembershipRepository ownerMembershipRepository;
    private final TaxFilingRepository taxFilingRepository;
    private final TaxService taxService;
    private final ObjectMapper objectMapper;

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
                markUnkindUnits();
                seedMissingUnits(root);
                backfillDetails(root);
                if (expenseRepository.count() == 0) {
                    int n = seedExpenses(root);
                    if (n > 0) log.info("Backfilled {} expense(s) from seed-data.json.", n);
                }
                seedMissingOwners(root);
                seedTaxFilings(root);
                log.info("Database already seeded with {} units ({} top-level).",
                        storageUnitRepository.count(), storageUnitRepository.findByParentIsNull().size());
                return;
            }
            log.info("Legacy demo data detected (unit A-101). Replacing it with the real data...");
            taxFilingRepository.deleteAll();
            ownershipRepository.deleteAll();
            ownerMembershipRepository.deleteAll();
            ownerRepository.deleteAll();
            expenseRepository.deleteAll();
            unitPriceHistoryRepository.deleteAll();
            paymentRepository.deleteAll();
            rentalAgreementRepository.deleteAll();
            clientRepository.deleteAll();
            storageUnitRepository.deleteAll();
        }

        log.info("Seeding database from seed-data.json (real data from the BBVA statements)...");

        Map<String, Object> root = parseSeedData();

        // 1. Clients
        Map<String, Client> clientsByName = seedClients(root, new HashMap<>());

        // 2. Units (locales first, then the trasteros inside them and the apartments)
        Map<String, StorageUnit> unitsByNumber = new HashMap<>();
        List<StorageUnit> units = seedUnits(root, unitsByNumber);

        // 3. Rental agreements
        Map<Integer, RentalAgreement> rentalsByRef = seedRentals(root, unitsByNumber, clientsByName, null);

        // 4. Payments (all PAID by bank transfer, from the statements)
        int paymentCount = seedPayments(root, rentalsByRef);

        // 5. Price history, derived from how each unit's rent evolved across tenancies
        seedPriceHistoryFromRentals(units);

        // 6. Expenses (outgoing side of both bank statements)
        int expenseCount = seedExpenses(root);

        // 7. Owners (persons and the comunidad de bienes with its members) and their shares in the units
        Map<String, Owner> ownersByName = seedOwners(root);
        int ownershipCount = seedOwnerships(root, ownersByName, unitsByNumber);

        // 8. Tax returns already filed (registered with a snapshot of the figures)
        int filingCount = seedTaxFilings(root);

        log.info("Seeding complete: {} clients, {} units, {} rentals, {} payments, {} price-history entries, {} expenses, {} owners, {} shares, {} tax filings.",
                clientsByName.size(), units.size(), rentalsByRef.size(), paymentCount,
                unitPriceHistoryRepository.count(), expenseCount, ownersByName.size(), ownershipCount, filingCount);
    }

    private static Map<String, Object> parseSeedData() throws java.io.IOException {
        String json = new String(new ClassPathResource("seed-data.json").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        return JsonParserFactory.getJsonParser().parseMap(json);
    }

    // ------------------------------------------------------------------
    // Building blocks (each one only creates what does not exist yet)
    // ------------------------------------------------------------------

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
                    .documentId(str(c, "documentId"))
                    .notes(str(c, "notes"))
                    .build());
            byName.put(client.getFullName(), client);
        }
        return byName;
    }

    /**
     * Units from the "units" array that are not yet in {@code unitsByNumber}; the map
     * is completed with the created ones. Entries may name a "parent" unit number (the
     * local they sit in); top-level units are created first so parents always exist.
     * Returns only the units created by this call.
     */
    @SuppressWarnings("unchecked")
    private List<StorageUnit> seedUnits(Map<String, Object> root, Map<String, StorageUnit> unitsByNumber) {
        List<StorageUnit> created = new ArrayList<>();
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Object o : (List<Object>) root.get("units")) {
            entries.add((Map<String, Object>) o);
        }
        // Two passes: units without parent, then the ones inside them
        for (int pass = 0; pass < 2; pass++) {
            for (Map<String, Object> u : entries) {
                boolean hasParent = str(u, "parent") != null;
                if (hasParent != (pass == 1)) continue;
                String number = str(u, "unitNumber");
                if (unitsByNumber.containsKey(number)) continue;

                StorageUnit parent = null;
                if (hasParent) {
                    parent = unitsByNumber.get(str(u, "parent"));
                    if (parent == null) {
                        log.warn("Unknown parent '{}' for unit {}; created as a top-level unit.", str(u, "parent"), number);
                    }
                }
                String kind = str(u, "kind");
                StorageUnit unit = storageUnitRepository.save(StorageUnit.builder()
                        .unitNumber(number)
                        .name(str(u, "name"))
                        .kind(kind == null ? UnitKind.STORAGE_UNIT : UnitKind.valueOf(kind))
                        .parent(parent)
                        .sizeSquareMeters(Double.valueOf(str(u, "sizeSquareMeters")))
                        .location(str(u, "location"))
                        .cadastralReference(str(u, "cadastralReference"))
                        .baseMonthlyRate(dec(u, "baseMonthlyRate"))
                        .status(UnitStatus.valueOf(str(u, "status")))
                        .description(str(u, "description"))
                        .build());
                unitsByNumber.put(unit.getUnitNumber(), unit);
                created.add(unit);
            }
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
            Client coClient = str(r, "coClient") != null ? clientsByName.get(str(r, "coClient")) : null;
            if (str(r, "coClient") != null && coClient == null) {
                log.warn("Rental ref {}: unknown second tenant '{}'.", r.get("ref"), str(r, "coClient"));
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
                    .coClient(coClient)
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
        List<StorageUnit> created = seedUnits(root, unitsByNumber);
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

    /**
     * Incremental load of details added to seed-data.json after a database was created:
     * clients' DNI / NIE, units' referencia catastral and the second tenant of a rental
     * (matched by unit and start date). Only fills what is still empty. Idempotent.
     */
    @SuppressWarnings("unchecked")
    private void backfillDetails(Map<String, Object> root) {
        int changes = 0;
        Map<String, Client> clientsByName = new HashMap<>();
        for (Client c : clientRepository.findAll()) {
            clientsByName.put(c.getFullName(), c);
        }
        for (Object o : (List<Object>) root.get("clients")) {
            Map<String, Object> c = (Map<String, Object>) o;
            String name = str(c, "fullName");
            Client client = clientsByName.get(name);
            if (client == null) {
                clientsByName = seedClients(root, clientsByName);
                changes++;
                continue;
            }
            if (client.getDocumentId() == null && str(c, "documentId") != null) {
                client.setDocumentId(str(c, "documentId"));
                clientRepository.save(client);
                changes++;
            }
        }

        Map<String, StorageUnit> unitsByNumber = new HashMap<>();
        for (StorageUnit u : storageUnitRepository.findAll()) {
            unitsByNumber.put(u.getUnitNumber(), u);
        }
        for (Object o : (List<Object>) root.get("units")) {
            Map<String, Object> u = (Map<String, Object>) o;
            StorageUnit unit = unitsByNumber.get(str(u, "unitNumber"));
            if (unit != null && unit.getCadastralReference() == null && str(u, "cadastralReference") != null) {
                unit.setCadastralReference(str(u, "cadastralReference"));
                storageUnitRepository.save(unit);
                changes++;
            }
        }

        for (Object o : (List<Object>) root.get("rentals")) {
            Map<String, Object> r = (Map<String, Object>) o;
            if (str(r, "coClient") == null) continue;
            StorageUnit unit = unitsByNumber.get(str(r, "unit"));
            Client coClient = clientsByName.get(str(r, "coClient"));
            if (unit == null || coClient == null) continue;
            LocalDate start = LocalDate.parse(str(r, "startDate"));
            for (RentalAgreement agreement : rentalAgreementRepository.findByStorageUnitId(unit.getId())) {
                if (agreement.getCoClient() == null && start.equals(agreement.getStartDate())) {
                    agreement.setCoClient(coClient);
                    rentalAgreementRepository.save(agreement);
                    changes++;
                }
            }
        }
        if (changes > 0) log.info("Backfilled {} client / unit / rental detail(s) from seed-data.json.", changes);
    }

    /** Rows created before unit kinds existed are storage units. Idempotent. */
    private void markUnkindUnits() {
        List<StorageUnit> unkindUnits = storageUnitRepository.findByKindIsNull();
        if (unkindUnits.isEmpty()) return;
        for (StorageUnit unit : unkindUnits) {
            unit.setKind(UnitKind.STORAGE_UNIT);
        }
        storageUnitRepository.saveAll(unkindUnits);
        log.info("Marked {} unit(s) without a kind as STORAGE_UNIT.", unkindUnits.size());
    }

    /**
     * Seeds the "expenses" array. An entry names the unit the cost belongs to
     * ("unit": "3", or "unit": "BD" for the costs of the storage local) or several
     * units ("units": ["3D", "3E"]) among which the amount is split evenly; an entry
     * without any is a general expense.
     */
    @SuppressWarnings("unchecked")
    private int seedExpenses(Map<String, Object> root) {
        List<Object> entries = (List<Object>) root.get("expenses");
        if (entries == null) return 0;

        Map<String, StorageUnit> unitsByNumber = new HashMap<>();
        for (StorageUnit u : storageUnitRepository.findAll()) {
            unitsByNumber.put(u.getUnitNumber(), u);
        }

        int count = 0;
        for (Object o : entries) {
            Map<String, Object> e = (Map<String, Object>) o;
            LocalDate date = LocalDate.parse(str(e, "date"));
            BigDecimal amount = dec(e, "amount");
            String description = str(e, "description");
            ExpenseCategory category = ExpenseCategory.valueOf(str(e, "category"));

            List<String> targets = new ArrayList<>();
            if (e.get("units") instanceof List<?> list) {
                for (Object n : list) targets.add(String.valueOf(n));
            } else if (str(e, "unit") != null) {
                targets.add(str(e, "unit"));
            }

            if (targets.isEmpty()) {
                expenseRepository.save(Expense.builder()
                        .expenseDate(date).amount(amount).description(description).category(category).build());
                count++;
                continue;
            }

            // Split evenly, giving the rounding remainder to the last unit
            BigDecimal each = amount.divide(BigDecimal.valueOf(targets.size()), 2, RoundingMode.HALF_UP);
            BigDecimal assigned = BigDecimal.ZERO;
            for (int i = 0; i < targets.size(); i++) {
                String number = targets.get(i);
                StorageUnit unit = unitsByNumber.get(number);
                if (unit == null) {
                    log.warn("Unknown unit '{}' for expense '{}'; seeded as a general expense.", number, description);
                }
                boolean last = i == targets.size() - 1;
                BigDecimal share = targets.size() == 1 ? amount : last ? amount.subtract(assigned) : each;
                assigned = assigned.add(share);
                String text = targets.size() == 1 ? description
                        : description + " (1/" + targets.size() + ", reparto " + String.join("/", targets) + ")";
                if (text.length() > 255) text = text.substring(0, 255);
                expenseRepository.save(Expense.builder()
                        .storageUnit(unit)
                        .expenseDate(date)
                        .amount(share)
                        .description(text)
                        .category(category)
                        .build());
                count++;
            }
        }
        return count;
    }

    /**
     * Owners from the "owners" array that do not exist yet, keyed by full name. An
     * entry has a "type" (PERSON by default) and, for a COMUNIDAD_DE_BIENES, its
     * "members" ({"owner", "share"}); members are linked once every owner exists.
     */
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
            String type = str(o, "type");
            Owner owner = ownerRepository.findByFullNameIgnoreCase(name)
                    .orElseGet(() -> ownerRepository.save(Owner.builder()
                            .fullName(name)
                            .type(type == null ? OwnerType.PERSON : OwnerType.valueOf(type))
                            .documentId(str(o, "documentId"))
                            .email(str(o, "email"))
                            .phone(str(o, "phone"))
                            .bankAccount(str(o, "bankAccount"))
                            .notes(str(o, "notes"))
                            .build()));
            byName.put(owner.getFullName(), owner);
        }
        // Members of the entities (skipped when the entity already has members)
        for (Object entry : entries) {
            Map<String, Object> o = (Map<String, Object>) entry;
            Owner entity = byName.get(str(o, "fullName"));
            if (entity == null || !entity.isEntity() || !(o.get("members") instanceof List<?> members)) continue;
            if (!ownerMembershipRepository.findByEntityId(entity.getId()).isEmpty()) continue;
            for (Object mo : members) {
                Map<String, Object> m = (Map<String, Object>) mo;
                Owner member = byName.get(str(m, "owner"));
                if (member == null || member.isEntity()) {
                    log.warn("Skipping member '{}' of '{}': unknown owner or not a person.", str(m, "owner"), entity.getFullName());
                    continue;
                }
                ownerMembershipRepository.save(OwnerMembership.builder()
                        .entity(entity)
                        .member(member)
                        .sharePercent(parseShare(m.get("share")))
                        .notes(str(m, "notes"))
                        .build());
            }
        }
        return byName;
    }

    /**
     * Shares from the "ownerships" array: each entry names an "owner", a "unit" and a
     * "share" given as a fraction ("1/6") or a percentage (16.6667). Entries whose
     * owner already holds a share of that unit are skipped.
     */
    @SuppressWarnings("unchecked")
    private int seedOwnerships(Map<String, Object> root, Map<String, Owner> ownersByName,
                               Map<String, StorageUnit> unitsByNumber) {
        List<Object> entries = (List<Object>) root.get("ownerships");
        if (entries == null) return 0;
        int count = 0;
        for (Object entry : entries) {
            Map<String, Object> s = (Map<String, Object>) entry;
            Owner owner = ownersByName.get(str(s, "owner"));
            StorageUnit unit = unitsByNumber.get(str(s, "unit"));
            if (owner == null || unit == null) {
                log.warn("Skipping share of '{}' in unit '{}': unknown owner or unit.", str(s, "owner"), str(s, "unit"));
                continue;
            }
            if (ownershipRepository.findByOwnerIdAndStorageUnitId(owner.getId(), unit.getId()).isPresent()) continue;
            ownershipRepository.save(Ownership.builder()
                    .owner(owner)
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
     * the owners, members and shares of seed-data.json are created.
     */
    private void seedMissingOwners(Map<String, Object> root) {
        if (ownerRepository.count() > 0) return;
        Map<String, StorageUnit> unitsByNumber = new HashMap<>();
        for (StorageUnit u : storageUnitRepository.findAll()) {
            unitsByNumber.put(u.getUnitNumber(), u);
        }
        Map<String, Owner> owners = seedOwners(root);
        int shares = seedOwnerships(root, owners, unitsByNumber);
        if (!owners.isEmpty()) log.info("Backfilled {} owner(s) and {} share(s) from seed-data.json.", owners.size(), shares);
    }

    /**
     * Tax returns already filed, from the "taxFilings" array. Each entry describes a
     * range of returns that were presented:
     * <ul>
     *   <li>MODELO_303: quarters "fromYear"/"fromQuarter" to "toYear"/"toQuarter",
     *       optionally scoped to an "owner" (the comunidad de bienes);</li>
     *   <li>MODELO_184: years "fromYear" to "toYear", one return per year for the
     *       "owner" (comunidad de bienes) or, when omitted, for every comunidad;</li>
     *   <li>IRPF: years "fromYear" to "toYear", one return per person with income
     *       that year.</li>
     * </ul>
     * Every return of a range not registered yet is created with a snapshot of the
     * report computed from the seeded data, its main figure as amount and the
     * statutory deadline as filing date. Idempotent: returns already registered are
     * left untouched, and auto-registered returns that fall outside the seeded
     * ranges (a range was shortened) are removed; hand-registered ones are never touched.
     */
    @SuppressWarnings("unchecked")
    private int seedTaxFilings(Map<String, Object> root) {
        List<Object> entries = (List<Object>) root.get("taxFilings");
        if (entries == null) return 0;
        Set<String> registered = new HashSet<>();
        for (TaxFiling f : taxFilingRepository.findAll()) {
            registered.add(filingKey(f.getModel(), f.getYear(), f.getQuarter(), f.getOwnerId()));
        }
        Set<String> inSeedRanges = new HashSet<>();
        int count = 0;
        for (Object entry : entries) {
            Map<String, Object> spec = (Map<String, Object>) entry;
            TaxModel model = TaxModel.valueOf(str(spec, "model"));
            int fromYear = ((Number) spec.get("fromYear")).intValue();
            int toYear = ((Number) spec.get("toYear")).intValue();
            String notes = str(spec, "notes");
            if (notes == null || !notes.contains(SEED_FILING_MARKER)) {
                notes = (notes == null ? "" : notes + " ") + "Registrado automáticamente desde " + SEED_FILING_MARKER + ".";
            }
            Owner specOwner = str(spec, "owner") == null ? null
                    : ownerRepository.findByFullNameIgnoreCase(str(spec, "owner")).orElse(null);

            switch (model) {
                case MODELO_303 -> {
                    int fromQuarter = ((Number) spec.get("fromQuarter")).intValue();
                    int toQuarter = ((Number) spec.get("toQuarter")).intValue();
                    Long ownerId = specOwner != null ? specOwner.getId() : null;
                    Map<Integer, Modelo303DTO> reportsByYear = new HashMap<>();
                    for (int index = fromYear * 4 + (fromQuarter - 1); index <= toYear * 4 + (toQuarter - 1); index++) {
                        int year = index / 4;
                        int quarter = index % 4 + 1;
                        String key = filingKey(model, year, quarter, null);
                        inSeedRanges.add(key);
                        if (!registered.add(key)) continue;
                        Modelo303DTO report = reportsByYear.computeIfAbsent(year, y -> taxService.modelo303(y, ownerId));
                        Modelo303DTO.Quarter q = report.getQuarters().get(quarter - 1);
                        taxFilingRepository.save(TaxFiling.builder()
                                .model(model)
                                .year(year)
                                .quarter(quarter)
                                .filedDate(modelo303Deadline(year, quarter))
                                .amount(q.getCollectedVat())
                                .description("IVA " + q.getLabel() + ": base " + q.getCollectedBase() + " EUR · cuota " + q.getCollectedVat() + " EUR")
                                .snapshot(toJson(report))
                                .notes(notes)
                                .build());
                        count++;
                    }
                }
                case MODELO_184 -> {
                    List<Owner> entities = specOwner != null ? List.of(specOwner)
                            : ownerRepository.findByTypeOrderByFullNameAsc(OwnerType.COMUNIDAD_DE_BIENES);
                    for (Owner entity : entities) {
                        for (int year = fromYear; year <= toYear; year++) {
                            String key = filingKey(model, year, null, entity.getId());
                            inSeedRanges.add(key);
                            if (!registered.add(key)) continue;
                            Modelo184DTO report = taxService.modelo184(year, entity.getId());
                            taxFilingRepository.save(TaxFiling.builder()
                                    .model(model)
                                    .year(year)
                                    .ownerId(entity.getId())
                                    .ownerName(entity.getFullName())
                                    .filedDate(modelo184Deadline(year))
                                    .amount(report.getAttributedBase())
                                    .description("Base atribuida " + year + ": " + report.getAttributedBase() + " EUR (" + entity.getFullName() + ")")
                                    .snapshot(toJson(report))
                                    .notes(notes)
                                    .build());
                            count++;
                        }
                    }
                }
                case IRPF -> {
                    for (int year = fromYear; year <= toYear; year++) {
                        IrpfReportDTO report = taxService.irpf(year);
                        String snapshot = toJson(report);
                        for (IrpfReportDTO.OwnerReport owner : report.getOwners()) {
                            String key = filingKey(model, year, null, owner.getOwnerId());
                            inSeedRanges.add(key);
                            if (!registered.add(key)) continue;
                            taxFilingRepository.save(TaxFiling.builder()
                                    .model(model)
                                    .year(year)
                                    .ownerId(owner.getOwnerId())
                                    .ownerName(owner.getOwnerName())
                                    .filedDate(irpfDeadline(year))
                                    .amount(owner.getTotalNet())
                                    .description("Alquileres neto " + owner.getRental().getNet() + " EUR + atribución de rentas "
                                            + owner.getAttribution().getIncomeBase() + " EUR")
                                    .snapshot(snapshot)
                                    .notes(notes)
                                    .build());
                            count++;
                        }
                    }
                }
            }
        }
        if (count > 0) log.info("Registered {} filed tax return(s) from seed-data.json.", count);

        for (TaxFiling f : taxFilingRepository.findAll()) {
            boolean seeded = f.getNotes() != null && f.getNotes().contains(SEED_FILING_MARKER);
            if (seeded && !inSeedRanges.contains(filingKey(f.getModel(), f.getYear(), f.getQuarter(), f.getOwnerId()))) {
                log.info("Removing auto-registered {} {} (no longer in the seeded ranges).", f.getModel(),
                        (f.getQuarter() != null ? f.getQuarter() + "T " : "") + f.getYear() + (f.getOwnerName() != null ? " · " + f.getOwnerName() : ""));
                taxFilingRepository.delete(f);
            }
        }
        return count;
    }

    private static String filingKey(TaxModel model, Integer year, Integer quarter, Long ownerId) {
        return model + ":" + year + ":" + quarter + ":" + ownerId;
    }

    private String toJson(Object report) {
        try {
            return objectMapper.writeValueAsString(report);
        } catch (tools.jackson.core.JacksonException e) {
            log.warn("Could not serialise a tax report: {}", e.getMessage());
            return null;
        }
    }

    /** Text every seeded filing carries in its notes, so they can be told apart from hand-registered ones. */
    public static final String SEED_FILING_MARKER = "seed-data.json";

    /** Statutory deadline of the Modelo 303 of a quarter: 20 Apr / 20 Jul / 20 Oct / 30 Jan of the next year. */
    public static LocalDate modelo303Deadline(int year, int quarter) {
        return switch (quarter) {
            case 1 -> LocalDate.of(year, 4, 20);
            case 2 -> LocalDate.of(year, 7, 20);
            case 3 -> LocalDate.of(year, 10, 20);
            default -> LocalDate.of(year + 1, 1, 30);
        };
    }

    /** Statutory deadline of the Modelo 184 of a year: last day of February of the next year. */
    public static LocalDate modelo184Deadline(int year) {
        return java.time.YearMonth.of(year + 1, 2).atEndOfMonth();
    }

    /** End of the IRPF campaign of a year: 30 June of the next year. */
    public static LocalDate irpfDeadline(int year) {
        return LocalDate.of(year + 1, 6, 30);
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
