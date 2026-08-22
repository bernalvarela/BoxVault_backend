package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.dto.ClientRequest;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.Client;
import com.storagemanager.storage_management.repository.ClientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ClientService {

    private final ClientRepository clientRepository;

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
