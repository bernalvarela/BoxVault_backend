package com.storagemanager.storage_management;

import com.storagemanager.storage_management.dto.ExpenseDocumentDTO;
import com.storagemanager.storage_management.dto.ExpenseRequest;
import com.storagemanager.storage_management.dto.UnitDocumentEntryDTO;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.Expense;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.model.enums.DocumentType;
import com.storagemanager.storage_management.model.enums.ExpenseCategory;
import com.storagemanager.storage_management.repository.DocumentRepository;
import com.storagemanager.storage_management.repository.ExpenseRepository;
import com.storagemanager.storage_management.repository.StorageUnitRepository;
import com.storagemanager.storage_management.repository.UnitDocumentEntryRepository;
import com.storagemanager.storage_management.service.ExpenseDocumentService;
import com.storagemanager.storage_management.service.ExpenseService;
import com.storagemanager.storage_management.service.StorageUnitService;
import com.storagemanager.storage_management.service.UnitDocumentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * El justificante de un pago va en el gasto, y la notificación de la licencia
 * —tres PDF— en una entrada de documentos de la unidad. Los dos se ven desde la
 * unidad. Cada prueba borra lo que crea.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class UnitAndExpenseDocumentsTest {

    @Autowired private ExpenseService expenseService;
    @Autowired private ExpenseDocumentService expenseDocuments;
    @Autowired private UnitDocumentService unitDocuments;
    @Autowired private StorageUnitService storageUnitService;
    @Autowired private ExpenseRepository expenses;
    @Autowired private StorageUnitRepository units;
    @Autowired private UnitDocumentEntryRepository entries;
    @Autowired private DocumentRepository documents;

    private final List<Long> expenseIds = new ArrayList<>();
    private final List<Long[]> entryIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        entryIds.forEach(ids -> { if (entries.existsById(ids[1])) unitDocuments.deleteEntry(ids[0], ids[1]); });
        expenseIds.forEach(id -> { if (expenses.existsById(id)) expenseService.deleteExpense(id); });
    }

    private StorageUnit anyUnit() {
        return units.findAll().stream().findFirst().orElseThrow();
    }

    private static MockMultipartFile pdf(String name) {
        return new MockMultipartFile("file", name, "application/pdf", ("%PDF-1.4 " + name).getBytes());
    }

    private Expense licenceFee(StorageUnit unit) {
        ExpenseRequest request = new ExpenseRequest();
        request.setStorageUnitId(unit.getId());
        request.setAmount(new BigDecimal("120.50"));
        request.setDescription("Tasa de la licencia");
        request.setCategory(ExpenseCategory.TRIBUTOS);
        request.setExpenseDate(LocalDate.of(2026, 11, 10));
        Expense expense = expenseService.createExpense(request);
        expenseIds.add(expense.getId());
        return expense;
    }

    @Test
    void elJustificanteVaEnElGastoYSeVeDesdeLaUnidad() {
        StorageUnit unit = anyUnit();
        Expense expense = licenceFee(unit);

        ExpenseDocumentDTO doc = expenseDocuments.upload(expense.getId(), pdf("carta-de-pago.pdf"), null, null);
        assertEquals(DocumentType.JUSTIFICANTE, doc.getDocumentType(), "sin decir nada, es el justificante");
        assertEquals(1, expenseDocuments.getDocuments(expense.getId()).size());
        assertEquals(1L, expenseDocuments.countByExpense().get(expense.getId()));

        ExpenseDocumentDTO seen = expenseDocuments.getDocumentsForUnit(unit.getId()).stream()
                .filter(d -> d.getId().equals(doc.getId())).findFirst().orElseThrow();
        assertEquals("Tasa de la licencia", seen.getExpenseDescription());
        assertEquals(0, new BigDecimal("120.50").compareTo(seen.getExpenseAmount()));

        assertThrows(BadRequestException.class, () ->
                expenseDocuments.upload(expense.getId(), pdf("dni.pdf"), DocumentType.DNI, null));
    }

    @Test
    void borrarElGastoBorraSusDocumentos() {
        Expense expense = licenceFee(anyUnit());
        Long docId = expenseDocuments.upload(expense.getId(), pdf("recibo.pdf"), DocumentType.FACTURA, null).getId();

        expenseService.deleteExpense(expense.getId());

        assertFalse(expenses.existsById(expense.getId()));
        assertFalse(documents.existsById(docId), "el fichero no se queda huérfano");
    }

    @Test
    void unaEntradaDeLaUnidadAgrupaVariosFicheros() {
        StorageUnit unit = anyUnit();
        UnitDocumentEntryDTO entry = unitDocuments.createEntry(unit.getId(), new UnitDocumentEntryDTO.Request(
                "  Notificación de licencia ", LocalDate.of(2026, 11, 5), "Plazo: 20 de diciembre"));
        entryIds.add(new Long[]{unit.getId(), entry.getId()});
        assertEquals("Notificación de licencia", entry.getTitle());

        unitDocuments.upload(unit.getId(), entry.getId(), pdf("notificacion.pdf"), DocumentType.NOTIFICACION, null);
        unitDocuments.upload(unit.getId(), entry.getId(), pdf("liquidacion.pdf"), DocumentType.NOTIFICACION, null);
        UnitDocumentEntryDTO full =
                unitDocuments.upload(unit.getId(), entry.getId(), pdf("instrucciones.pdf"), null, null);
        assertEquals(3, full.getDocuments().size());
        assertEquals(DocumentType.OTRO, full.getDocuments().get(2).getDocumentType());

        UnitDocumentEntryDTO listed = unitDocuments.getEntries(unit.getId()).stream()
                .filter(e -> e.getId().equals(entry.getId())).findFirst().orElseThrow();
        assertEquals(3, listed.getDocuments().size());
        assertTrue(listed.getDocuments().get(0).getDownloadUrl()
                .startsWith("/api/storages/" + unit.getId() + "/document-entries/" + entry.getId()));

        // Quitar un fichero deja la entrada; borrar la entrada se lleva los ficheros.
        Long firstDoc = listed.getDocuments().get(0).getId();
        assertEquals(2, unitDocuments.deleteDocument(unit.getId(), entry.getId(), firstDoc).getDocuments().size());
        assertFalse(documents.existsById(firstDoc));

        List<Long> rest = listed.getDocuments().stream().skip(1).map(UnitDocumentEntryDTO.FileDTO::getId).toList();
        unitDocuments.deleteEntry(unit.getId(), entry.getId());
        assertFalse(entries.existsById(entry.getId()));
        rest.forEach(id -> assertFalse(documents.existsById(id)));
    }

    @Test
    void elTipoYLaDescripcionDeUnFicheroSeCambianSinVolverASubirlo() {
        StorageUnit unit = anyUnit();
        UnitDocumentEntryDTO entry = unitDocuments.createEntry(unit.getId(),
                new UnitDocumentEntryDTO.Request("Licencia", LocalDate.of(2026, 1, 1), null));
        entryIds.add(new Long[]{unit.getId(), entry.getId()});
        Long docId = unitDocuments.upload(unit.getId(), entry.getId(), pdf("carta.pdf"), null, null)
                .getDocuments().get(0).getId();

        UnitDocumentEntryDTO.FileDTO changed = unitDocuments.updateDocument(unit.getId(), entry.getId(), docId,
                DocumentType.JUSTIFICANTE, "  Carta de pago  ").getDocuments().get(0);
        assertEquals(DocumentType.JUSTIFICANTE, changed.getDocumentType());
        assertEquals("Carta de pago", changed.getDescription());
        assertEquals("carta.pdf", changed.getFileName(), "el fichero es el mismo");

        assertNull(unitDocuments.updateDocument(unit.getId(), entry.getId(), docId, null, " ")
                .getDocuments().get(0).getDescription(), "vacía, se quita");
        assertThrows(BadRequestException.class, () ->
                unitDocuments.updateDocument(unit.getId(), entry.getId(), docId, DocumentType.DNI, null));
    }

    @Test
    void unGastoRelacionadoSaleDentroDeLaEntradaYNoSuelto() {
        StorageUnit unit = anyUnit();
        Expense expense = licenceFee(unit);
        Long docId = expenseDocuments.upload(expense.getId(), pdf("carta-de-pago.pdf"), null, null).getId();
        UnitDocumentEntryDTO entry = unitDocuments.createEntry(unit.getId(),
                new UnitDocumentEntryDTO.Request("Notificación de licencia", LocalDate.of(2026, 11, 5), null));
        entryIds.add(new Long[]{unit.getId(), entry.getId()});

        unitDocuments.linkExpense(unit.getId(), entry.getId(), expense.getId());
        unitDocuments.linkExpense(unit.getId(), entry.getId(), expense.getId()); // otra vez: no pasa nada

        UnitDocumentEntryDTO listed = unitDocuments.getEntries(unit.getId(), true).stream()
                .filter(e -> e.getId().equals(entry.getId())).findFirst().orElseThrow();
        assertEquals(1, listed.getExpenses().size());
        assertEquals("Tasa de la licencia", listed.getExpenses().get(0).getDescription());
        assertEquals(docId, listed.getExpenses().get(0).getDocuments().get(0).getId());
        assertTrue(expenseDocuments.getDocumentsForUnit(unit.getId()).stream()
                .noneMatch(d -> d.getExpenseId().equals(expense.getId())), "no sale también suelto");
        assertNull(unitDocuments.getEntries(unit.getId(), false).get(0).getExpenses(),
                "sin permiso de gastos no vienen");

        // En otra entrada no puede ir a la vez.
        UnitDocumentEntryDTO other = unitDocuments.createEntry(unit.getId(),
                new UnitDocumentEntryDTO.Request("Otra", LocalDate.of(2026, 11, 6), null));
        entryIds.add(new Long[]{unit.getId(), other.getId()});
        assertThrows(BadRequestException.class, () ->
                unitDocuments.linkExpense(unit.getId(), other.getId(), expense.getId()));

        // Quitar la relación lo devuelve a la lista suelta; borrar el gasto la quita sola.
        unitDocuments.unlinkExpense(unit.getId(), entry.getId(), expense.getId());
        assertTrue(expenseDocuments.getDocumentsForUnit(unit.getId()).stream()
                .anyMatch(d -> d.getExpenseId().equals(expense.getId())));
        unitDocuments.linkExpense(unit.getId(), other.getId(), expense.getId());
        expenseService.deleteExpense(expense.getId());
        assertTrue(unitDocuments.getEntries(unit.getId(), true).stream()
                .allMatch(e -> e.getExpenses().isEmpty()));
    }

    @Test
    void soloSeRelacionanGastosDeLaMismaUnidad() {
        List<StorageUnit> all = units.findAll();
        StorageUnit unit = all.get(0);
        Expense foreign = licenceFee(all.get(1));
        UnitDocumentEntryDTO entry = unitDocuments.createEntry(unit.getId(),
                new UnitDocumentEntryDTO.Request("Licencia", LocalDate.of(2026, 1, 1), null));
        entryIds.add(new Long[]{unit.getId(), entry.getId()});

        assertThrows(BadRequestException.class, () ->
                unitDocuments.linkExpense(unit.getId(), entry.getId(), foreign.getId()));
    }

    @Test
    void laEntradaSoloSeVeDesdeSuUnidad() {
        List<StorageUnit> all = units.findAll();
        StorageUnit unit = all.get(0);
        StorageUnit other = all.get(1);
        UnitDocumentEntryDTO entry = unitDocuments.createEntry(unit.getId(),
                new UnitDocumentEntryDTO.Request("Licencia", LocalDate.of(2026, 1, 1), null));
        entryIds.add(new Long[]{unit.getId(), entry.getId()});

        assertThrows(ResourceNotFoundException.class, () ->
                unitDocuments.upload(other.getId(), entry.getId(), pdf("x.pdf"), null, null));
        assertThrows(BadRequestException.class, () ->
                unitDocuments.upload(unit.getId(), entry.getId(), pdf("x.pdf"), DocumentType.DNI, null));
    }

    @Test
    void unaUnidadConDocumentosNoSeBorra() {
        // Una unidad propia, libre: sin gastos, sin unidades dentro y sin alquilar,
        // para que lo único que impida borrarla sean sus documentos. Los datos
        // sembrados no tienen por qué tener ninguna así.
        StorageUnit unit = units.save(StorageUnit.builder()
                .unitNumber("TEST-DOCS-" + System.nanoTime())
                .name("Unidad de prueba de documentos")
                .sizeSquareMeters(1.0)
                .baseMonthlyRate(new BigDecimal("10.00"))
                .build());
        try {
            UnitDocumentEntryDTO entry = unitDocuments.createEntry(unit.getId(),
                    new UnitDocumentEntryDTO.Request("Certificado", LocalDate.of(2026, 1, 1), null));

            BadRequestException e = assertThrows(BadRequestException.class,
                    () -> storageUnitService.deleteUnit(unit.getId()));
            assertTrue(e.getMessage().contains("document"));

            // Sin la entrada, ya se puede borrar.
            unitDocuments.deleteEntry(unit.getId(), entry.getId());
            storageUnitService.deleteUnit(unit.getId());
            assertFalse(units.existsById(unit.getId()));
        } finally {
            entries.findByStorageUnitIdOrderByEntryDateDescIdDesc(unit.getId())
                    .forEach(e -> unitDocuments.deleteEntry(unit.getId(), e.getId()));
            if (units.existsById(unit.getId())) units.deleteById(unit.getId());
        }
    }
}
