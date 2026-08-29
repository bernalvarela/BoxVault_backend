package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.dto.ClientDTO;
import com.storagemanager.storage_management.dto.ClientRequest;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.Client;
import com.storagemanager.storage_management.repository.ClientRepository;
import com.storagemanager.storage_management.repository.RentalAgreementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ClientService {

    private final ClientRepository clientRepository;
    private final RentalAgreementRepository rentalAgreementRepository;

    public List<Client> getAllClients() {
        return clientRepository.findAll();
    }

    public Client getClientById(Long id) {
        return clientRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Client not found with id: " + id));
    }

    public List<Client> searchClients(String query) {
        if (query == null || query.trim().isEmpty()) {
            return getAllClients();
        }
        return clientRepository.searchClients(query.trim());
    }

    public List<ClientDTO> searchClientSummaries(String query) {
        // A client is active when they are the main or the second tenant of an ACTIVE contract
        Map<Long, Long> activeRentalsByClient = new java.util.HashMap<>();
        for (var r : rentalAgreementRepository.findAllActiveRentals()) {
            activeRentalsByClient.merge(r.getClient().getId(), 1L, Long::sum);
            if (r.getCoClient() != null) activeRentalsByClient.merge(r.getCoClient().getId(), 1L, Long::sum);
        }

        return searchClients(query).stream()
                .map(c -> ClientDTO.builder()
                        .id(c.getId())
                        .fullName(c.getFullName())
                        .email(c.getEmail())
                        .phone(c.getPhone())
                        .documentId(c.getDocumentId())
                        .address(c.getAddress())
                        .emergencyContact(c.getEmergencyContact())
                        .notes(c.getNotes())
                        .createdAt(c.getCreatedAt())
                        .updatedAt(c.getUpdatedAt())
                        .active(activeRentalsByClient.containsKey(c.getId()))
                        .activeRentalsCount(activeRentalsByClient.getOrDefault(c.getId(), 0L))
                        .build())
                .toList();
    }

    @Transactional
    public Client createClient(ClientRequest request) {
        if (clientRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new BadRequestException("Client with email " + request.getEmail() + " already exists");
        }

        Client client = Client.builder()
                .fullName(request.getFullName())
                .email(request.getEmail())
                .phone(request.getPhone())
                .documentId(request.getDocumentId())
                .address(request.getAddress())
                .emergencyContact(request.getEmergencyContact())
                .notes(request.getNotes())
                .build();

        return clientRepository.save(client);
    }

    @Transactional
    public Client updateClient(Long id, ClientRequest request) {
        Client client = getClientById(id);

        if (!client.getEmail().equalsIgnoreCase(request.getEmail()) &&
                clientRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new BadRequestException("Client with email " + request.getEmail() + " already exists");
        }

        client.setFullName(request.getFullName());
        client.setEmail(request.getEmail());
        client.setPhone(request.getPhone());
        client.setDocumentId(request.getDocumentId());
        client.setAddress(request.getAddress());
        client.setEmergencyContact(request.getEmergencyContact());
        client.setNotes(request.getNotes());

        return clientRepository.save(client);
    }

    @Transactional
    public void deleteClient(Long id) {
        Client client = getClientById(id);
        clientRepository.delete(client);
    }
}
