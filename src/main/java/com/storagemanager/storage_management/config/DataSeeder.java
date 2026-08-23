package com.storagemanager.storage_management.config;

import com.storagemanager.storage_management.model.Client;
import com.storagemanager.storage_management.model.Payment;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.model.enums.*;
import com.storagemanager.storage_management.repository.ClientRepository;
import com.storagemanager.storage_management.repository.PaymentRepository;
import com.storagemanager.storage_management.repository.RentalAgreementRepository;
import com.storagemanager.storage_management.repository.StorageUnitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final StorageUnitRepository storageUnitRepository;
    private final ClientRepository clientRepository;
    private final RentalAgreementRepository rentalAgreementRepository;
    private final PaymentRepository paymentRepository;

    @Override
    public void run(String... args) {
        if (storageUnitRepository.count() > 0) {
            log.info("Database already seeded with {} storage units.", storageUnitRepository.count());
            return;
        }

        log.info("Seeding database with 9 storages, clients, rentals, and payments...");

        // 1. Seed 9 Storage Units
        StorageUnit u1 = storageUnitRepository.save(StorageUnit.builder()
                .unitNumber("A-101")
                .name("Unit 1 - Compact Standard")
                .sizeSquareMeters(5.0)
                .dimensions("2.0m x 2.5m x 3.0m")
                .location("Building A - Ground Floor")
                .baseMonthlyRate(new BigDecimal("60.00"))
                .status(UnitStatus.OCCUPIED)
                .description("Ideal for boxes, seasonal items, and small furniture.")
                .build());

        StorageUnit u2 = storageUnitRepository.save(StorageUnit.builder()
                .unitNumber("A-102")
                .name("Unit 2 - Small Standard")
                .sizeSquareMeters(6.0)
                .dimensions("2.0m x 3.0m x 3.0m")
                .location("Building A - Ground Floor")
                .baseMonthlyRate(new BigDecimal("70.00"))
                .status(UnitStatus.AVAILABLE)
                .description("Great for luggage, bicycles, and household storage.")
                .build());

        StorageUnit u3 = storageUnitRepository.save(StorageUnit.builder()
                .unitNumber("A-103")
                .name("Unit 3 - Medium Climate Controlled")
                .sizeSquareMeters(10.0)
                .dimensions("2.5m x 4.0m x 3.0m")
                .location("Building A - Ground Floor (Climate Wing)")
                .baseMonthlyRate(new BigDecimal("120.00"))
                .status(UnitStatus.OCCUPIED)
                .description("Perfect for electronics, artwork, documents, and wooden antiques.")
                .build());

        StorageUnit u4 = storageUnitRepository.save(StorageUnit.builder()
                .unitNumber("B-201")
                .name("Unit 4 - Large Climate Controlled")
                .sizeSquareMeters(12.0)
                .dimensions("3.0m x 4.0m x 3.0m")
                .location("Building B - First Floor")
                .baseMonthlyRate(new BigDecimal("140.00"))
                .status(UnitStatus.OCCUPIED)
                .description("Suitable for full 2-bedroom apartment furniture and inventory.")
                .build());

        StorageUnit u5 = storageUnitRepository.save(StorageUnit.builder()
                .unitNumber("B-202")
                .name("Unit 5 - Medium Drive-Up")
                .sizeSquareMeters(16.0)
                .dimensions("4.0m x 4.0m x 3.2m")
                .location("Building B - External Bay")
                .baseMonthlyRate(new BigDecimal("180.00"))
                .status(UnitStatus.OCCUPIED)
                .description("Direct drive-up loading for equipment, trade tools, and large goods.")
                .build());

        StorageUnit u6 = storageUnitRepository.save(StorageUnit.builder()
                .unitNumber("B-203")
                .name("Unit 6 - Large Drive-Up")
                .sizeSquareMeters(20.0)
                .dimensions("4.0m x 5.0m x 3.2m")
                .location("Building B - External Bay")
                .baseMonthlyRate(new BigDecimal("220.00"))
                .status(UnitStatus.OCCUPIED)
                .description("Large capacity for commercial inventory, vehicle, or 3-4 bedroom house contents.")
                .build());

        StorageUnit u7 = storageUnitRepository.save(StorageUnit.builder()
                .unitNumber("C-301")
                .name("Unit 7 - Extra Large Commercial")
                .sizeSquareMeters(25.0)
                .dimensions("5.0m x 5.0m x 3.5m")
                .location("Building C - Yard Level")
                .baseMonthlyRate(new BigDecimal("275.00"))
                .status(UnitStatus.AVAILABLE)
                .description("Commercial warehouse storage for businesses and distribution.")
                .build());

        StorageUnit u8 = storageUnitRepository.save(StorageUnit.builder()
                .unitNumber("C-302")
                .name("Unit 8 - High-Security Vault")
                .sizeSquareMeters(8.0)
                .dimensions("2.5m x 3.2m x 3.0m")
                .location("Building C - Secure Inner Vault")
                .baseMonthlyRate(new BigDecimal("160.00"))
                .status(UnitStatus.OCCUPIED)
                .description("Maximum security storage for luxury valuables, collectibles, and critical archives.")
                .build());

        StorageUnit u9 = storageUnitRepository.save(StorageUnit.builder()
                .unitNumber("C-303")
                .name("Unit 9 - Medium Standard")
                .sizeSquareMeters(10.0)
                .dimensions("2.5m x 4.0m x 3.0m")
                .location("Building C - Ground Floor")
                .baseMonthlyRate(new BigDecimal("110.00"))
                .status(UnitStatus.MAINTENANCE)
                .description("Currently undergoing routine maintenance on roller door mechanism.")
                .build());

        // 2. Seed Clients
        Client c1 = clientRepository.save(Client.builder()
                .fullName("Elena Martinez")
                .email("elena.martinez@example.com")
                .phone("+34 611 234 567")
                .documentId("48291038X")
                .address("Calle Mayor 14, 28013 Madrid")
                .emergencyContact("Pablo Martinez (+34 611 999 888)")
                .notes("Long-term personal items storage.")
                .build());

        Client c2 = clientRepository.save(Client.builder()
                .fullName("Carlos Gomez")
                .email("carlos.gomez@example.com")
                .phone("+34 622 345 678")
                .documentId("71829304Y")
                .address("Av. Diagonal 450, 08006 Barcelona")
                .emergencyContact("Maria Gomez (+34 622 888 777)")
                .notes("Vintage vinyl and audio gear collection.")
                .build());

        Client c3 = clientRepository.save(Client.builder()
                .fullName("Sophia Müller")
                .email("sophia.muller@example.com")
                .phone("+34 633 456 789")
                .documentId("X9283741Z")
                .address("Paseo de la Castellana 88, Madrid")
                .emergencyContact("Lukas Müller (+49 170 1234567)")
                .notes("Art gallery temporary exhibition stock.")
                .build());

        Client c4 = clientRepository.save(Client.builder()
                .fullName("David Henderson")
                .email("david.henderson@example.com")
                .phone("+34 644 567 890")
                .documentId("Y8374619B")
                .address("Calle Gran Via 22, Valencia")
                .emergencyContact("Emma Henderson (+34 644 111 222)")
                .notes("Construction & renovation tooling storage.")
                .build());

        Client c5 = clientRepository.save(Client.builder()
                .fullName("Laura Rossi")
                .email("laura.rossi@example.com")
                .phone("+34 655 678 901")
                .documentId("52910482K")
                .address("Calle Alcala 120, Madrid")
                .emergencyContact("Marco Rossi (+39 340 1234567)")
                .notes("E-commerce fashion apparel stock.")
                .build());

        Client c6 = clientRepository.save(Client.builder()
                .fullName("Marcus Sterling")
                .email("marcus.sterling@example.com")
                .phone("+34 666 789 012")
                .documentId("39482019M")
                .address("Calle Serrano 54, Madrid")
                .emergencyContact("Legal Counsel (+34 912 345 678)")
                .notes("Corporate confidential physical archives and secure records.")
                .build());

        // 3. Seed Rental Agreements
        RentalAgreement r1 = rentalAgreementRepository.save(RentalAgreement.builder()
                .agreementNumber("RNT-2026-001")
                .storageUnit(u1)
                .client(c1)
                .startDate(LocalDate.of(2026, 1, 1))
                .billingDayOfMonth(1)
                .monthlyRent(new BigDecimal("60.00"))
                .securityDeposit(new BigDecimal("120.00"))
                .depositPaid(true)
                .status(RentalStatus.ACTIVE)
                .autoRenew(true)
                .notes("Standard 1-year contract")
                .build());

        RentalAgreement r2 = rentalAgreementRepository.save(RentalAgreement.builder()
                .agreementNumber("RNT-2026-002")
                .storageUnit(u3)
                .client(c2)
                .startDate(LocalDate.of(2026, 2, 1))
                .billingDayOfMonth(5)
                .monthlyRent(new BigDecimal("120.00"))
                .securityDeposit(new BigDecimal("240.00"))
                .depositPaid(true)
                .status(RentalStatus.ACTIVE)
                .autoRenew(true)
                .notes("Climate control contract with 24/7 access pass")
                .build());

        RentalAgreement r3 = rentalAgreementRepository.save(RentalAgreement.builder()
                .agreementNumber("RNT-2026-003")
                .storageUnit(u4)
                .client(c3)
                .startDate(LocalDate.of(2026, 1, 15))
                .billingDayOfMonth(15)
                .monthlyRent(new BigDecimal("140.00"))
                .securityDeposit(new BigDecimal("280.00"))
                .depositPaid(true)
                .status(RentalStatus.ACTIVE)
                .autoRenew(true)
                .notes("Special climate condition contract")
                .build());

        RentalAgreement r4 = rentalAgreementRepository.save(RentalAgreement.builder()
                .agreementNumber("RNT-2026-004")
                .storageUnit(u5)
                .client(c4)
                .startDate(LocalDate.of(2026, 3, 1))
                .billingDayOfMonth(1)
                .monthlyRent(new BigDecimal("180.00"))
                .securityDeposit(new BigDecimal("360.00"))
                .depositPaid(true)
                .status(RentalStatus.ACTIVE)
                .autoRenew(true)
                .notes("Contract with vehicle access permit")
                .build());

        RentalAgreement r5 = rentalAgreementRepository.save(RentalAgreement.builder()
                .agreementNumber("RNT-2026-005")
                .storageUnit(u6)
                .client(c5)
                .startDate(LocalDate.of(2026, 4, 1))
                .billingDayOfMonth(10)
                .monthlyRent(new BigDecimal("220.00"))
                .securityDeposit(new BigDecimal("440.00"))
                .depositPaid(true)
                .status(RentalStatus.ACTIVE)
                .autoRenew(true)
                .notes("Commercial business contract")
                .build());

        RentalAgreement r6 = rentalAgreementRepository.save(RentalAgreement.builder()
                .agreementNumber("RNT-2026-006")
                .storageUnit(u8)
                .client(c6)
                .startDate(LocalDate.of(2026, 5, 1))
                .billingDayOfMonth(1)
                .monthlyRent(new BigDecimal("160.00"))
                .securityDeposit(new BigDecimal("500.00"))
                .depositPaid(true)
                .status(RentalStatus.ACTIVE)
                .autoRenew(true)
                .notes("High security vault agreement with dual-key authentication")
                .build());

        // 4. Seed Payment History (March 2026 to August 2026)
        seedPaymentsForMonth(r1, 2026, 3, new BigDecimal("60.00"), PaymentStatus.PAID, PaymentMethod.BANK_TRANSFER, LocalDate.of(2026, 3, 1), "TRX-2026-0301-A");
        seedPaymentsForMonth(r1, 2026, 4, new BigDecimal("60.00"), PaymentStatus.PAID, PaymentMethod.BANK_TRANSFER, LocalDate.of(2026, 4, 1), "TRX-2026-0401-A");
        seedPaymentsForMonth(r1, 2026, 5, new BigDecimal("60.00"), PaymentStatus.PAID, PaymentMethod.CREDIT_CARD, LocalDate.of(2026, 5, 2), "TRX-2026-0502-A");
        seedPaymentsForMonth(r1, 2026, 6, new BigDecimal("60.00"), PaymentStatus.PAID, PaymentMethod.BANK_TRANSFER, LocalDate.of(2026, 6, 1), "TRX-2026-0601-A");
        seedPaymentsForMonth(r1, 2026, 7, new BigDecimal("60.00"), PaymentStatus.PAID, PaymentMethod.BANK_TRANSFER, LocalDate.of(2026, 7, 1), "TRX-2026-0701-A");
        seedPaymentsForMonth(r1, 2026, 8, new BigDecimal("60.00"), PaymentStatus.PAID, PaymentMethod.BANK_TRANSFER, LocalDate.of(2026, 8, 1), "TRX-2026-0801-A");

        seedPaymentsForMonth(r2, 2026, 3, new BigDecimal("120.00"), PaymentStatus.PAID, PaymentMethod.CREDIT_CARD, LocalDate.of(2026, 3, 5), "TRX-2026-0305-B");
        seedPaymentsForMonth(r2, 2026, 4, new BigDecimal("120.00"), PaymentStatus.PAID, PaymentMethod.CREDIT_CARD, LocalDate.of(2026, 4, 5), "TRX-2026-0405-B");
        seedPaymentsForMonth(r2, 2026, 5, new BigDecimal("120.00"), PaymentStatus.PAID, PaymentMethod.CREDIT_CARD, LocalDate.of(2026, 5, 5), "TRX-2026-0505-B");
        seedPaymentsForMonth(r2, 2026, 6, new BigDecimal("120.00"), PaymentStatus.PAID, PaymentMethod.CREDIT_CARD, LocalDate.of(2026, 6, 4), "TRX-2026-0604-B");
        seedPaymentsForMonth(r2, 2026, 7, new BigDecimal("120.00"), PaymentStatus.PAID, PaymentMethod.CREDIT_CARD, LocalDate.of(2026, 7, 5), "TRX-2026-0705-B");
        seedPaymentsForMonth(r2, 2026, 8, new BigDecimal("120.00"), PaymentStatus.PAID, PaymentMethod.CREDIT_CARD, LocalDate.of(2026, 8, 5), "TRX-2026-0805-B");

        seedPaymentsForMonth(r3, 2026, 3, new BigDecimal("140.00"), PaymentStatus.PAID, PaymentMethod.BANK_TRANSFER, LocalDate.of(2026, 3, 15), "TRX-2026-0315-C");
        seedPaymentsForMonth(r3, 2026, 4, new BigDecimal("140.00"), PaymentStatus.PAID, PaymentMethod.BANK_TRANSFER, LocalDate.of(2026, 4, 15), "TRX-2026-0415-C");
        seedPaymentsForMonth(r3, 2026, 5, new BigDecimal("140.00"), PaymentStatus.PAID, PaymentMethod.BANK_TRANSFER, LocalDate.of(2026, 5, 15), "TRX-2026-0515-C");
        seedPaymentsForMonth(r3, 2026, 6, new BigDecimal("140.00"), PaymentStatus.PAID, PaymentMethod.BANK_TRANSFER, LocalDate.of(2026, 6, 14), "TRX-2026-0614-C");
        seedPaymentsForMonth(r3, 2026, 7, new BigDecimal("140.00"), PaymentStatus.PAID, PaymentMethod.BANK_TRANSFER, LocalDate.of(2026, 7, 15), "TRX-2026-0715-C");
        seedPaymentsForMonth(r3, 2026, 8, new BigDecimal("140.00"), PaymentStatus.PAID, PaymentMethod.BANK_TRANSFER, LocalDate.of(2026, 8, 15), "TRX-2026-0815-C");

        seedPaymentsForMonth(r4, 2026, 3, new BigDecimal("180.00"), PaymentStatus.PAID, PaymentMethod.DIRECT_DEBIT, LocalDate.of(2026, 3, 1), "TRX-2026-0301-D");
        seedPaymentsForMonth(r4, 2026, 4, new BigDecimal("180.00"), PaymentStatus.PAID, PaymentMethod.DIRECT_DEBIT, LocalDate.of(2026, 4, 1), "TRX-2026-0401-D");
        seedPaymentsForMonth(r4, 2026, 5, new BigDecimal("180.00"), PaymentStatus.PAID, PaymentMethod.DIRECT_DEBIT, LocalDate.of(2026, 5, 1), "TRX-2026-0501-D");
        seedPaymentsForMonth(r4, 2026, 6, new BigDecimal("180.00"), PaymentStatus.PAID, PaymentMethod.DIRECT_DEBIT, LocalDate.of(2026, 6, 1), "TRX-2026-0601-D");
        seedPaymentsForMonth(r4, 2026, 7, new BigDecimal("180.00"), PaymentStatus.OVERDUE, null, null, null);
        seedPaymentsForMonth(r4, 2026, 8, new BigDecimal("180.00"), PaymentStatus.PENDING, null, null, null);

        seedPaymentsForMonth(r5, 2026, 4, new BigDecimal("220.00"), PaymentStatus.PAID, PaymentMethod.BANK_TRANSFER, LocalDate.of(2026, 4, 10), "TRX-2026-0410-E");
        seedPaymentsForMonth(r5, 2026, 5, new BigDecimal("220.00"), PaymentStatus.PAID, PaymentMethod.BANK_TRANSFER, LocalDate.of(2026, 5, 10), "TRX-2026-0510-E");
        seedPaymentsForMonth(r5, 2026, 6, new BigDecimal("220.00"), PaymentStatus.PAID, PaymentMethod.BANK_TRANSFER, LocalDate.of(2026, 6, 10), "TRX-2026-0610-E");
        seedPaymentsForMonth(r5, 2026, 7, new BigDecimal("220.00"), PaymentStatus.PAID, PaymentMethod.BANK_TRANSFER, LocalDate.of(2026, 7, 10), "TRX-2026-0710-E");
        seedPaymentsForMonth(r5, 2026, 8, new BigDecimal("220.00"), PaymentStatus.PENDING, null, null, null);

        seedPaymentsForMonth(r6, 2026, 5, new BigDecimal("160.00"), PaymentStatus.PAID, PaymentMethod.BANK_TRANSFER, LocalDate.of(2026, 5, 1), "TRX-2026-0501-F");
        seedPaymentsForMonth(r6, 2026, 6, new BigDecimal("160.00"), PaymentStatus.PAID, PaymentMethod.BANK_TRANSFER, LocalDate.of(2026, 6, 1), "TRX-2026-0601-F");
        seedPaymentsForMonth(r6, 2026, 7, new BigDecimal("160.00"), PaymentStatus.PAID, PaymentMethod.BANK_TRANSFER, LocalDate.of(2026, 7, 1), "TRX-2026-0701-F");
        seedPaymentsForMonth(r6, 2026, 8, new BigDecimal("160.00"), PaymentStatus.PAID, PaymentMethod.BANK_TRANSFER, LocalDate.of(2026, 8, 1), "TRX-2026-0801-F");

        log.info("Database seeding completed successfully! 9 Storages initialized.");
    }

    private void seedPaymentsForMonth(RentalAgreement rental, int year, int month, BigDecimal amount,
                                      PaymentStatus status, PaymentMethod method, LocalDate paidDate, String ref) {
        int day = Math.min(rental.getBillingDayOfMonth(), 28);
        LocalDate dueDate = LocalDate.of(year, month, day);

        paymentRepository.save(Payment.builder()
                .rentalAgreement(rental)
                .storageUnit(rental.getStorageUnit())
                .client(rental.getClient())
                .billingPeriodMonth(month)
                .billingPeriodYear(year)
                .amountDue(amount)
                .amountPaid(status == PaymentStatus.PAID ? amount : BigDecimal.ZERO)
                .dueDate(dueDate)
                .paymentDate(paidDate)
                .status(status)
                .paymentMethod(method)
                .transactionReference(ref)
                .build());
    }
}
