package com.storagemanager.storage_management;

import com.storagemanager.storage_management.dto.ExpenseRequest;
import com.storagemanager.storage_management.dto.TaxNoticeDTO;
import com.storagemanager.storage_management.dto.TaxNoticeDocumentDTO;
import com.storagemanager.storage_management.dto.TaxNoticePaymentRequest;
import com.storagemanager.storage_management.dto.TaxNoticeRequest;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.model.Expense;
import com.storagemanager.storage_management.model.Owner;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.model.TaxFiling;
import com.storagemanager.storage_management.model.enums.DocumentType;
import com.storagemanager.storage_management.model.enums.ExpenseCategory;
import com.storagemanager.storage_management.model.enums.OwnerType;
import com.storagemanager.storage_management.model.enums.TaxModel;
import com.storagemanager.storage_management.model.enums.TaxNoticeKind;
import com.storagemanager.storage_management.model.enums.TaxNoticeStatus;
import com.storagemanager.storage_management.repository.ExpenseRepository;
import com.storagemanager.storage_management.repository.OwnerRepository;
import com.storagemanager.storage_management.repository.StorageUnitRepository;
import com.storagemanager.storage_management.repository.TaxFilingRepository;
import com.storagemanager.storage_management.repository.TaxNoticeRepository;
import com.storagemanager.storage_management.service.ExpenseService;
import com.storagemanager.storage_management.service.TaxNoticeService;
import com.storagemanager.storage_management.service.TaxService;
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
 * Una sanción por presentar tarde el 184 de la comunidad de bienes: se registra,
 * se enlaza sola con la declaración, guarda sus documentos y su pago se apunta
 * como gasto SANCIONES, que no es deducible. Cada prueba borra lo que crea.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class TaxNoticeTest {

    @Autowired private TaxNoticeService notices;
    @Autowired private TaxNoticeRepository noticeRepository;
    @Autowired private TaxFilingRepository filings;
    @Autowired private OwnerRepository owners;
    @Autowired private StorageUnitRepository units;
    @Autowired private ExpenseService expenseService;
    @Autowired private ExpenseRepository expenses;

    private final List<Long> noticeIds = new ArrayList<>();
    private final List<Long> expenseIds = new ArrayList<>();
    private TaxFiling filing;

    @AfterEach
    void cleanUp() {
        noticeIds.forEach(id -> { if (noticeRepository.existsById(id)) notices.delete(id); });
        expenseIds.forEach(id -> { if (expenses.existsById(id)) expenses.deleteById(id); });
        if (filing != null) filings.deleteById(filing.getId());
    }

    private Owner community() {
        return owners.findByTypeOrderByFullNameAsc(OwnerType.COMUNIDAD_DE_BIENES).stream().findFirst()
                .orElseThrow(() -> new AssertionError("no hay comunidad de bienes en los datos sembrados"));
    }

    private StorageUnit anyUnit() {
        return units.findAll().stream().findFirst().orElseThrow();
    }

    private TaxNoticeRequest sanction184(Owner owner, int year) {
        TaxNoticeRequest request = new TaxNoticeRequest();
        request.setKind(TaxNoticeKind.SANCION);
        request.setModel(TaxModel.MODELO_184);
        request.setYear(year);
        request.setOwnerId(owner.getId());
        request.setReference("2026GRC18400001");
        request.setReceivedOn(LocalDate.of(2026, 10, 6));
        request.setPaymentDeadline(LocalDate.of(2026, 11, 20));
        request.setAmount(new BigDecimal("300.00"));
        request.setReducedAmount(new BigDecimal("225.00"));
        return request;
    }

    private TaxNoticeDTO create(TaxNoticeRequest request) {
        TaxNoticeDTO dto = notices.create(request);
        noticeIds.add(dto.getId());
        return dto;
    }

    @Test
    void aSanctionIsLinkedToItsFilingAndKeepsItsDocuments() {
        Owner cb = community();
        filing = filings.save(TaxFiling.builder()
                .model(TaxModel.MODELO_184).year(2001).ownerId(cb.getId()).ownerName(cb.getFullName())
                .filedDate(LocalDate.of(2002, 3, 15)).build());

        TaxNoticeDTO notice = create(sanction184(cb, 2001));

        assertEquals(TaxNoticeStatus.PENDIENTE, notice.getStatus());
        assertEquals(filing.getId(), notice.getFilingId(), "se enlaza sola con el 184 registrado de ese año");
        assertEquals(cb.getFullName(), notice.getOwnerName());
        assertEquals(0, new BigDecimal("225.00").compareTo(notice.getReducedAmount()));

        TaxNoticeDocumentDTO doc = notices.upload(notice.getId(),
                new MockMultipartFile("file", "notificacion.pdf", "application/pdf", "%PDF-1.4 prueba".getBytes()),
                null, "Acuerdo de imposición de sanción");
        assertEquals(DocumentType.NOTIFICACION, doc.getDocumentType(), "sin decir tipo, es la notificación");
        assertEquals(1, notices.getNotice(notice.getId()).getDocumentCount());

        assertThrows(BadRequestException.class, () -> notices.upload(notice.getId(),
                new MockMultipartFile("file", "dni.jpg", "image/jpeg", new byte[]{1, 2, 3}), DocumentType.DNI, null),
                "un DNI no va en una notificación");
    }

    @Test
    void payingCreatesANonDeductibleExpense() {
        TaxNoticeDTO notice = create(sanction184(community(), 2002));
        StorageUnit unit = anyUnit();

        TaxNoticePaymentRequest payment = new TaxNoticePaymentRequest();
        payment.setPaidOn(LocalDate.of(2026, 10, 20));
        payment.setPaidAmount(new BigDecimal("225.00"));
        payment.setStorageUnitId(unit.getId());
        TaxNoticeDTO paid = notices.pay(notice.getId(), payment);
        expenseIds.add(paid.getExpenseId());

        assertEquals(TaxNoticeStatus.PAGADA, paid.getStatus());
        assertNotNull(paid.getExpenseId());
        Expense expense = expenses.findById(paid.getExpenseId()).orElseThrow();
        assertEquals(ExpenseCategory.SANCIONES, expense.getCategory());
        assertEquals(0, new BigDecimal("225.00").compareTo(expense.getAmount()));
        assertTrue(expense.getDescription().startsWith("Sanción Modelo 184 2002"), expense.getDescription());
        assertFalse(TaxService.DEDUCTIBLE_CATEGORIES.contains(ExpenseCategory.SANCIONES), "una sanción nunca es deducible");

        TaxNoticeDTO undone = notices.unpay(notice.getId());
        assertEquals(TaxNoticeStatus.PENDIENTE, undone.getStatus());
        assertNull(undone.getExpenseId());
        assertTrue(expenses.existsById(expense.getId()), "deshacer el pago no borra el gasto");
    }

    @Test
    void linkingTheBankChargeMovesItOutOfImpuestos() {
        TaxNoticeDTO notice = create(sanction184(community(), 2003));
        ExpenseRequest charge = new ExpenseRequest();
        charge.setAmount(new BigDecimal("225.00"));
        charge.setCategory(ExpenseCategory.IMPUESTOS);
        charge.setDescription("RECIBO AEAT 2026GRC18400001");
        charge.setExpenseDate(LocalDate.of(2026, 10, 21));
        charge.setStorageUnitId(anyUnit().getId());
        Expense bankCharge = expenseService.createExpense(charge);
        expenseIds.add(bankCharge.getId());

        TaxNoticePaymentRequest payment = new TaxNoticePaymentRequest();
        payment.setPaidOn(LocalDate.of(2026, 10, 21));
        payment.setPaidAmount(new BigDecimal("225.00"));
        payment.setExpenseId(bankCharge.getId());
        TaxNoticeDTO paid = notices.pay(notice.getId(), payment);

        assertEquals(bankCharge.getId(), paid.getExpenseId());
        assertEquals(ExpenseCategory.SANCIONES, expenses.findById(bankCharge.getId()).orElseThrow().getCategory(),
                "el cargo de la AEAT deja de contar como cuota de impuestos");
    }

    @Test
    void deadlinesBeforeReceivingItAreRejected() {
        TaxNoticeRequest request = sanction184(community(), 2004);
        request.setPaymentDeadline(LocalDate.of(2026, 9, 1));
        assertThrows(BadRequestException.class, () -> notices.create(request));
    }
}
