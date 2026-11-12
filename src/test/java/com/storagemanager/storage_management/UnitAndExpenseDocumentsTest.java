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
        StorageUnit unit = units.findAll().stream()
                .filter(u -> units.countByParentId(u.getId()) == 0 && expenses.countByStorageUnitId(u.getId()) == 0)
                .filter(u -> u.getStatus() != com.storagemanager.storage_management.model.enums.UnitStatus.OCCUPIED)
                .findFirst().orElseThrow();
        UnitDocumentEntryDTO entry = unitDocuments.createEntry(unit.getId(),
                new UnitDocumentEntryDTO.Request("Certificado", LocalDate.of(2026, 1, 1), null));
        entryIds.add(new Long[]{unit.getId(), entry.getId()});

        BadRequestException e = assertThrows(BadRequestException.class, () -> storageUnitService.deleteUnit(unit.getId()));
        assertTrue(e.getMessage().contains("document"));
    }
}
