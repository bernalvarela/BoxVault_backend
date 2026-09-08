package com.storagemanager.storage_management.controller;

import com.storagemanager.storage_management.dto.ClientDTO;
import com.storagemanager.storage_management.dto.ClientRequest;
import com.storagemanager.storage_management.model.Client;
import com.storagemanager.storage_management.model.Payment;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.service.ClientService;
import com.storagemanager.storage_management.service.PaymentService;
import com.storagemanager.storage_management.service.RentalAgreementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/clients")
@RequiredArgsConstructor

public class ClientController {

    private final ClientService clientService;
    private final RentalAgreementService rentalAgreementService;
    private final PaymentService paymentService;

    @PreAuthorize("@access.can('CLIENTES','LEER')")
    @GetMapping
    public ResponseEntity<List<ClientDTO>> getAllClients(@RequestParam(required = false) String search) {
        return ResponseEntity.ok(clientService.searchClientSummaries(search));
    }

    @PreAuthorize("@access.can('CLIENTES','LEER')")
    @GetMapping("/{id}")
    public ResponseEntity<Client> getClientById(@PathVariable Long id) {
        return ResponseEntity.ok(clientService.getClientById(id));
    }

    @PreAuthorize("@access.can('CLIENTES','LEER')")
    @GetMapping("/{id}/rentals")
    public ResponseEntity<List<RentalAgreement>> getClientRentals(@PathVariable Long id) {
        return ResponseEntity.ok(rentalAgreementService.getAgreementsByClient(id));
    }

    @PreAuthorize("@access.can('CLIENTES','LEER')")
    @GetMapping("/{id}/payments")
    public ResponseEntity<List<Payment>> getClientPayments(@PathVariable Long id) {
        return ResponseEntity.ok(paymentService.getPaymentsByClient(id));
    }

    @PreAuthorize("@access.can('CLIENTES','ESCRIBIR')")
    @PostMapping
    public ResponseEntity<Client> createClient(@Valid @RequestBody ClientRequest request) {
        return new ResponseEntity<>(clientService.createClient(request), HttpStatus.CREATED);
    }

    @PreAuthorize("@access.can('CLIENTES','ESCRIBIR')")
    @PutMapping("/{id}")
    public ResponseEntity<Client> updateClient(@PathVariable Long id, @Valid @RequestBody ClientRequest request) {
        return ResponseEntity.ok(clientService.updateClient(id, request));
    }

    @PreAuthorize("@access.can('CLIENTES','ADMINISTRAR')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteClient(@PathVariable Long id) {
        clientService.deleteClient(id);
        return ResponseEntity.noContent().build();
    }
}
