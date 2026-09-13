package com.storagemanager.storage_management;

import com.storagemanager.storage_management.config.DataSeeder;
import com.storagemanager.storage_management.dto.IrpfReportDTO;
import com.storagemanager.storage_management.dto.Modelo184DTO;
import com.storagemanager.storage_management.dto.Modelo303DTO;
import com.storagemanager.storage_management.dto.OwnerDTO;
import com.storagemanager.storage_management.dto.TaxFilingDTO;
import com.storagemanager.storage_management.dto.TaxFilingRequest;
import com.storagemanager.storage_management.model.Expense;
import com.storagemanager.storage_management.model.enums.ExpenseCategory;
import com.storagemanager.storage_management.model.enums.OwnerType;
import com.storagemanager.storage_management.model.enums.TaxModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class TaxControllerTest {

    /** A year fully covered by the seeded bank statements (trasteros and apartments). */
    private static final int YEAR = 2025;

    @Autowired
    private TestRestTemplate restTemplate;

    private static boolean sameMoney(BigDecimal a, BigDecimal b) {
        return a.subtract(b).abs().compareTo(new BigDecimal("0.05")) <= 0;
    }

    private OwnerDTO ownerNamed(String prefix) {
        OwnerDTO[] owners = restTemplate.getForEntity("/api/owners", OwnerDTO[].class).getBody();
        assertNotNull(owners);
        return Arrays.stream(owners).filter(o -> o.getFullName().startsWith(prefix)).findFirst().orElseThrow();
    }

    @Test
    void modelo303CoversTheTrasterosOfTheComunidad() {
        ResponseEntity<Modelo303DTO> response = restTemplate.getForEntity("/api/taxes/modelo-303?year=" + YEAR, Modelo303DTO.class);
        assertEquals(200, response.getStatusCode().value());
        Modelo303DTO report = response.getBody();
        assertNotNull(report);
        assertEquals(YEAR, report.getYear());
        assertEquals(4, report.getQuarters().size());
        // Only VAT-bearing rentable units: the trasteros, not the flats nor the locales
        assertTrue(report.getUnitNumbers().stream().allMatch(n -> n.matches("[1-9]")), report.getUnitNumbers().toString());
        assertEquals(9, report.getUnitCount());
        assertTrue(report.getCollectedBase().compareTo(BigDecimal.ZERO) > 0);

        BigDecimal quarters = BigDecimal.ZERO;
        for (Modelo303DTO.Quarter q : report.getQuarters()) {
            assertEquals(q.getQuarter() + "T " + YEAR, q.getLabel());
            assertEquals(0, q.getCollectedBase().add(q.getCollectedVat()).compareTo(q.getCollectedTotal()));
            if (q.getCollectedBase().signum() > 0) {
                assertTrue(sameMoney(q.getCollectedBase().multiply(new BigDecimal("0.21")), q.getCollectedVat()), q.toString());
            }
            assertEquals(q.getPaidCount(), q.getPayments().stream().filter(p -> "PAID".equals(p.getStatus())).count());
            quarters = quarters.add(q.getCollectedBase());
        }
        assertEquals(0, quarters.compareTo(report.getCollectedBase()));

        // Scoped to the comunidad (owner of the local): the same trasteros; scoped to Marta: nothing with VAT
        OwnerDTO entity = ownerNamed(DataSeeder.ENTITY_NAME);
        Modelo303DTO scoped = restTemplate.getForEntity("/api/taxes/modelo-303?year=" + YEAR + "&ownerId=" + entity.getId(), Modelo303DTO.class).getBody();
        assertNotNull(scoped);
        assertEquals(entity.getId(), scoped.getOwnerId());
        assertEquals(9, scoped.getUnitCount());
        assertEquals(0, report.getCollectedBase().compareTo(scoped.getCollectedBase()));
        Modelo303DTO marta = restTemplate.getForEntity("/api/taxes/modelo-303?year=" + YEAR + "&ownerId=" + ownerNamed("Marta").getId(), Modelo303DTO.class).getBody();
        assertNotNull(marta);
        assertEquals(0, marta.getUnitCount());
    }

    @Test
    void modelo184AttributesTheEntityIncomeToItsMembers() {
        ResponseEntity<Modelo184DTO> response = restTemplate.getForEntity("/api/taxes/modelo-184?year=" + YEAR, Modelo184DTO.class);
        assertEquals(200, response.getStatusCode().value());
        Modelo184DTO report = response.getBody();
        assertNotNull(report);
        assertEquals(DataSeeder.ENTITY_NAME, report.getEntityName());
        // Los 9 trasteros, que son los que dan renta, más los dos locales: no se
        // alquilan, pero soportan los gastos (IBI, luz, seguro) y por eso cuentan
        assertEquals(11, report.getUnitCount(), "The 9 trasteros plus the two locales that carry the expenses");
        assertTrue(report.getUnits().stream().filter(u -> !u.isInherited())
                .allMatch(u -> u.getUnitNumber().startsWith("B")), "sólo los bajos son propiedad directa");
        assertTrue(report.getUnits().stream().anyMatch(u -> "BD".equals(u.getUnitNumber())
                && u.getExpenses().compareTo(BigDecimal.ZERO) > 0), "el local del negocio soporta gastos");
        assertTrue(report.getIncomeBase().compareTo(BigDecimal.ZERO) > 0);

        // La comunidad no tributa: atribuye el rendimiento neto, no el ingreso íntegro
        assertTrue(report.getExpenses().compareTo(BigDecimal.ZERO) > 0, "the comunidad has deductible expenses");
        assertEquals(0, report.getIncomeBase().subtract(report.getExpenses()).compareTo(report.getNetBase()));
        assertTrue(report.getNetBase().compareTo(report.getIncomeBase()) < 0);

        // Four members adding up to 100 %: everything is attributed
        assertEquals(4, report.getMembers().size());
        assertTrue(new BigDecimal("100").subtract(report.getMembersSharePercent()).abs().compareTo(new BigDecimal("0.001")) < 0);
        assertTrue(sameMoney(report.getAttributedBase(), report.getIncomeBase()));
        assertTrue(sameMoney(report.getAttributedNet(), report.getNetBase()));
        BigDecimal membersTotal = BigDecimal.ZERO;
        BigDecimal membersNet = BigDecimal.ZERO;
        for (Modelo184DTO.Member m : report.getMembers()) {
            membersTotal = membersTotal.add(m.getIncomeBase());
            membersNet = membersNet.add(m.getNet());
            BigDecimal expected = report.getIncomeBase().multiply(m.getSharePercent()).divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP);
            assertEquals(0, expected.compareTo(m.getIncomeBase()), "Member " + m.getOwnerName());
            assertEquals(0, m.getIncomeBase().subtract(m.getExpenses()).compareTo(m.getNet()), "Member " + m.getOwnerName());
        }
        assertTrue(sameMoney(membersTotal, report.getAttributedBase()));
        assertTrue(sameMoney(membersNet, report.getAttributedNet()));
        assertTrue(report.getMembers().stream().noneMatch(m -> m.getOwnerName().startsWith("Marta")));

        // A person cannot file a Modelo 184
        assertEquals(400, restTemplate.getForEntity("/api/taxes/modelo-184?year=" + YEAR + "&ownerId=" + ownerNamed("Marta").getId(), Map.class).getStatusCode().value());
    }

    @Test
    void irpfSplitsDirectRentalsFromAttributedIncome() {
        ResponseEntity<IrpfReportDTO> response = restTemplate.getForEntity("/api/taxes/irpf?year=" + YEAR, IrpfReportDTO.class);
        assertEquals(200, response.getStatusCode().value());
        IrpfReportDTO report = response.getBody();
        assertNotNull(report);
        assertTrue(report.getEntityNames().contains(DataSeeder.ENTITY_NAME));
        assertTrue(report.getTotalRentalIncomeBase().compareTo(BigDecimal.ZERO) > 0, "The flats produce direct rental income");
        assertTrue(report.getTotalRentalExpenses().compareTo(BigDecimal.ZERO) > 0, "The flats have community / IBI expenses");
        assertTrue(report.getTotalAttributionIncomeBase().compareTo(BigDecimal.ZERO) > 0);
        assertTrue(report.getEntityExpenses().compareTo(BigDecimal.ZERO) > 0, "The local's expenses belong to the comunidad");
        assertFalse(report.getDeductibleCategories().contains("REPARTO_BENEFICIOS"));
        assertTrue(report.getDeductibleCategories().containsAll(List.of("COMUNIDAD", "SEGUROS", "TRIBUTOS")));

        for (IrpfReportDTO.OwnerReport owner : report.getOwners()) {
            IrpfReportDTO.Section rental = owner.getRental();
            IrpfReportDTO.Section attribution = owner.getAttribution();
            assertEquals(0, rental.getIncomeBase().subtract(rental.getExpenses()).compareTo(rental.getNet()));
            // Lo que declara cada persona es el neto de sus alquileres más el neto atribuido
            assertEquals(0, rental.getNet().add(attribution.getNet()).compareTo(owner.getTotalNet()));
            assertEquals(0, attribution.getIncomeBase().subtract(attribution.getExpenses()).compareTo(attribution.getNet()));
            for (IrpfReportDTO.Line line : rental.getLines()) {
                assertEquals("UNIT", line.getScope());
                assertTrue(line.getUnitNumber().matches("[13][DE]"), "Only the flats are held directly by persons: " + line.getUnitNumber());
                assertEquals(0, line.getIncomeBase().subtract(line.getExpenses()).compareTo(line.getNet()));
            }
            for (IrpfReportDTO.Line line : attribution.getLines()) {
                assertEquals("ENTITY", line.getScope());
                assertEquals(DataSeeder.ENTITY_NAME, line.getEntityName());
                // La comunidad atribuye su rendimiento neto: los gastos van dentro
                assertTrue(line.getExpenses().compareTo(BigDecimal.ZERO) > 0, "la comunidad tiene gastos que descontar");
                assertEquals(0, line.getIncomeBase().subtract(line.getExpenses()).compareTo(line.getNet()));
            }
        }

        // Xiao: 1/2 of both flats directly and 1/6 of the comunidad; her attribution equals her Modelo 184 line
        IrpfReportDTO.OwnerReport xiao = report.getOwners().stream().filter(o -> o.getOwnerName().startsWith("Xiao")).findFirst().orElseThrow();
        assertEquals(2, xiao.getRental().getLines().size());
        assertEquals(1, xiao.getAttribution().getLines().size());
        Modelo184DTO m184 = restTemplate.getForEntity("/api/taxes/modelo-184?year=" + YEAR, Modelo184DTO.class).getBody();
        assertNotNull(m184);
        Modelo184DTO.Member xiao184 = m184.getMembers().stream().filter(m -> m.getOwnerName().startsWith("Xiao")).findFirst().orElseThrow();
        assertEquals(0, xiao184.getIncomeBase().compareTo(xiao.getAttribution().getIncomeBase()));
        assertEquals(0, xiao184.getNet().compareTo(xiao.getAttribution().getNet()), "el 184 y el IRPF atribuyen el mismo neto");

        // Marta only has 1/4 of 3D: direct rental income, nothing attributed; the comunidad itself is not in the IRPF
        IrpfReportDTO.OwnerReport marta = report.getOwners().stream().filter(o -> o.getOwnerName().startsWith("Marta")).findFirst().orElseThrow();
        assertEquals(1, marta.getRental().getLines().size());
        assertTrue(marta.getAttribution().getLines().isEmpty());

        // The contract detail carries what the return needs: referencia catastral, tenants with DNI / NIE and the start date
        IrpfReportDTO.Line flat3d = marta.getRental().getLines().get(0);
        assertEquals("3D", flat3d.getUnitNumber());
        assertEquals("9602605NJ4090S0007JT", flat3d.getCadastralReference());
        IrpfReportDTO.Rental gabriela = flat3d.getRentals().stream().filter(r -> r.getClientName().startsWith("Gabriela")).findFirst().orElseThrow();
        assertEquals("Y-8033348-Z", gabriela.getClientDocumentId());
        assertEquals("Jofer Fernando Ramírez Jáuregui", gabriela.getCoClientName());
        assertEquals("Y-9905319-S", gabriela.getCoClientDocumentId());
        assertEquals(LocalDate.of(2023, 9, 1), gabriela.getStartDate());
        IrpfReportDTO.Line flat3e = xiao.getRental().getLines().stream().filter(l -> "3E".equals(l.getUnitNumber())).findFirst().orElseThrow();
        assertEquals("9602605NJ4090S0008KY", flat3e.getCadastralReference());
        // Carmen's contract on 3E starts in January 2026, so her DNI shows up on the 2026 return
        IrpfReportDTO report2026 = restTemplate.getForEntity("/api/taxes/irpf?year=2026", IrpfReportDTO.class).getBody();
        assertNotNull(report2026);
        IrpfReportDTO.Line flat3e2026 = report2026.getOwners().stream().filter(o -> o.getOwnerName().startsWith("Xiao")).findFirst().orElseThrow()
                .getRental().getLines().stream().filter(l -> "3E".equals(l.getUnitNumber())).findFirst().orElseThrow();
        assertTrue(flat3e2026.getRentals().stream().anyMatch(r -> "23.020.088-D".equals(r.getClientDocumentId())), "Carmen's DNI on the 3E contract");
        assertTrue(report.getOwners().stream().noneMatch(o -> DataSeeder.ENTITY_NAME.equals(o.getOwnerName())));
    }

    private List<TaxFilingDTO> seededFilings(TaxModel model) {
        ResponseEntity<TaxFilingDTO[]> response = restTemplate.getForEntity("/api/taxes/filings?model=" + model, TaxFilingDTO[].class);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        return Arrays.stream(response.getBody())
                .filter(f -> f.getNotes() != null && f.getNotes().contains(DataSeeder.SEED_FILING_MARKER))
                .toList();
    }

    /**
     * El registro del 303 no se inventa: cada declaración sale de un pago a la AEAT
     * de los gastos, con su fecha y su importe, y lleva al lado lo que la aplicación
     * calcula hoy para ese trimestre (que no tiene por qué coincidir).
     */
    @Test
    void modelo303FilingsComeFromThePaymentsToTheAeat() {
        Expense[] expenses = restTemplate.getForEntity("/api/expenses", Expense[].class).getBody();
        assertNotNull(expenses);
        List<Expense> aeatPayments = Arrays.stream(expenses)
                .filter(e -> e.getCategory() == ExpenseCategory.IMPUESTOS)
                .filter(e -> e.getDescription() != null && e.getDescription().contains("AEAT"))
                .toList();
        assertFalse(aeatPayments.isEmpty(), "the statements have AEAT tax payments");

        ResponseEntity<TaxFilingDTO[]> response =
                restTemplate.getForEntity("/api/taxes/filings?model=MODELO_303", TaxFilingDTO[].class);
        assertEquals(200, response.getStatusCode().value());
        List<TaxFilingDTO> filings = Arrays.asList(response.getBody());
        assertEquals(aeatPayments.size(), filings.size(), "one filing per payment to the AEAT");

        for (TaxFilingDTO f : filings) {
            Expense payment = aeatPayments.stream()
                    .filter(e -> e.getId().equals(f.getExpenseId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Filing " + f.getQuarter() + "T " + f.getYear()
                            + " is not linked to any AEAT payment"));
            // Lo registrado es lo que se ingresó de verdad, con la fecha del cargo
            assertEquals(payment.getExpenseDate(), f.getFiledDate());
            assertEquals(0, payment.getAmount().compareTo(f.getAmount()), f.getDescription());
            assertNotNull(f.getSnapshot());
            assertTrue(f.getSnapshot().contains("\"quarters\""));
            // Y al lado, lo que la aplicación calcula hoy para ese trimestre
            assertNotNull(f.getComputedAmount(), f.getDescription());
            assertEquals(0, f.getAmount().subtract(f.getComputedAmount()).compareTo(f.getDifference()));
            // El cargo se hace en el trimestre siguiente al que declara
            int paymentMonth = payment.getExpenseDate().getMonthValue();
            int declaredIn = paymentMonth <= 2 ? 4 : (paymentMonth - 3) / 3 + 1;
            assertEquals(declaredIn, f.getQuarter().intValue(), payment.getDescription());
        }
    }

    @Test
    void seededYearlyFilingsCoverTheFiledYears() {
        OwnerDTO entity = ownerNamed(DataSeeder.ENTITY_NAME);
        assertEquals(OwnerType.COMUNIDAD_DE_BIENES, entity.getType());

        // Modelo 184: one per year filed by the comunidad, amount = base attributed to its members
        List<TaxFilingDTO> m184 = seededFilings(TaxModel.MODELO_184);
        assertEquals(2, m184.size());
        for (TaxFilingDTO f : m184) {
            assertTrue(f.getYear() == 2024 || f.getYear() == 2025, "Year " + f.getYear());
            assertNull(f.getQuarter());
            assertEquals(entity.getId(), f.getOwnerId());
            assertEquals(DataSeeder.modelo184Deadline(f.getYear()), f.getFiledDate());
            Modelo184DTO live = restTemplate.getForEntity("/api/taxes/modelo-184?year=" + f.getYear(), Modelo184DTO.class).getBody();
            assertNotNull(live);
            assertEquals(0, live.getAttributedBase().compareTo(f.getAmount()));
        }

        // IRPF: one per person with income that year, from the first rental (2021; flats only until the trasteros started in 2024)
        List<TaxFilingDTO> irpf = seededFilings(TaxModel.IRPF);
        IrpfReportDTO irpf2023 = restTemplate.getForEntity("/api/taxes/irpf?year=2023", IrpfReportDTO.class).getBody();
        assertNotNull(irpf2023);
        // Juan owns the first-floor flats directly, so he is listed in 2023 with rental lines only:
        // the comunidad had no income that year, hence nothing is attributed to its members.
        IrpfReportDTO.OwnerReport juan2023 = irpf2023.getOwners().stream()
                .filter(o -> o.getOwnerName().startsWith("Juan")).findFirst().orElseThrow();
        assertEquals(2, juan2023.getRental().getLines().size(), "1E and 1D");
        assertTrue(juan2023.getAttribution().getLines().isEmpty(),
                "Members get no attribution in years the comunidad had no income");
        assertTrue(irpf2023.getOwners().stream().anyMatch(o -> o.getOwnerName().startsWith("Xiao")));
        for (int year : new int[]{2021, 2022, 2023, 2024, 2025}) {
            IrpfReportDTO live = restTemplate.getForEntity("/api/taxes/irpf?year=" + year, IrpfReportDTO.class).getBody();
            assertNotNull(live);
            final int y = year;
            List<TaxFilingDTO> ofYear = irpf.stream().filter(f -> f.getYear() == y).toList();
            assertEquals(live.getOwners().size(), ofYear.size(), "IRPF filings of " + year);
            for (IrpfReportDTO.OwnerReport owner : live.getOwners()) {
                TaxFilingDTO f = ofYear.stream().filter(x -> owner.getOwnerId().equals(x.getOwnerId())).findFirst().orElseThrow();
                assertEquals(owner.getOwnerName(), f.getOwnerName());
                assertEquals(0, owner.getTotalNet().compareTo(f.getAmount()));
                assertEquals(DataSeeder.irpfDeadline(year), f.getFiledDate());
            }
        }
        assertTrue(irpf.stream().noneMatch(f -> f.getYear() == 2026), "The 2026 IRPF is not filed yet");
    }

    @Test
    void filedReturnsAreRegisteredWithTheirSnapshot() {
        TaxFilingRequest request = new TaxFilingRequest();
        request.setModel(TaxModel.MODELO_303);
        request.setYear(YEAR);
        request.setFiledDate(LocalDate.of(YEAR, 4, 15));
        request.setAmount(new BigDecimal("123.45"));
        request.setDescription("IVA 1T " + YEAR);
        request.setSnapshot("{\"year\":" + YEAR + "}");

        // The 303 needs a quarter
        assertEquals(400, restTemplate.postForEntity("/api/taxes/filings", request, Map.class).getStatusCode().value());
        request.setQuarter(1);
        ResponseEntity<TaxFilingDTO> created = restTemplate.postForEntity("/api/taxes/filings", request, TaxFilingDTO.class);
        assertEquals(201, created.getStatusCode().value());
        assertNotNull(created.getBody());
        assertEquals(1, created.getBody().getQuarter());
        assertEquals("{\"year\":" + YEAR + "}", created.getBody().getSnapshot());

        // The 184 needs the comunidad, the IRPF a person; both record the owner's name
        OwnerDTO entity = ownerNamed(DataSeeder.ENTITY_NAME);
        TaxFilingRequest m184 = new TaxFilingRequest();
        m184.setModel(TaxModel.MODELO_184);
        m184.setYear(YEAR);
        m184.setFiledDate(LocalDate.of(YEAR + 1, 2, 10));
        m184.setSnapshot("{}");
        assertEquals(400, restTemplate.postForEntity("/api/taxes/filings", m184, Map.class).getStatusCode().value());
        m184.setOwnerId(entity.getId());
        ResponseEntity<TaxFilingDTO> m184Created = restTemplate.postForEntity("/api/taxes/filings", m184, TaxFilingDTO.class);
        assertEquals(201, m184Created.getStatusCode().value());
        assertNotNull(m184Created.getBody());
        assertEquals(entity.getFullName(), m184Created.getBody().getOwnerName());

        OwnerDTO person = ownerNamed("Xiao");
        TaxFilingRequest irpf = new TaxFilingRequest();
        irpf.setModel(TaxModel.IRPF);
        irpf.setYear(YEAR);
        irpf.setFiledDate(LocalDate.of(YEAR + 1, 6, 1));
        irpf.setSnapshot("{}");
        assertEquals(400, restTemplate.postForEntity("/api/taxes/filings", irpf, Map.class).getStatusCode().value());
        irpf.setOwnerId(person.getId());
        ResponseEntity<TaxFilingDTO> irpfCreated = restTemplate.postForEntity("/api/taxes/filings", irpf, TaxFilingDTO.class);
        assertEquals(201, irpfCreated.getStatusCode().value());
        assertNotNull(irpfCreated.getBody());
        assertEquals(person.getFullName(), irpfCreated.getBody().getOwnerName());

        // Listing, filtered by model
        ResponseEntity<TaxFilingDTO[]> list = restTemplate.getForEntity("/api/taxes/filings?model=MODELO_303&year=" + YEAR, TaxFilingDTO[].class);
        assertNotNull(list.getBody());
        assertTrue(Arrays.stream(list.getBody()).anyMatch(f -> f.getId().equals(created.getBody().getId())));
        assertTrue(Arrays.stream(list.getBody()).allMatch(f -> f.getModel() == TaxModel.MODELO_303));

        // Cleanup
        for (Long id : List.of(created.getBody().getId(), m184Created.getBody().getId(), irpfCreated.getBody().getId())) {
            assertEquals(204, restTemplate.exchange("/api/taxes/filings/" + id, HttpMethod.DELETE, null, Void.class).getStatusCode().value());
        }
        assertEquals(404, restTemplate.getForEntity("/api/taxes/filings/" + created.getBody().getId(), Map.class).getStatusCode().value());
    }
}
