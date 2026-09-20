package com.storagemanager.storage_management.service;

import com.storagemanager.storage_management.dto.ClientDTO;
import com.storagemanager.storage_management.dto.ClientRequest;
import com.storagemanager.storage_management.exception.BadRequestException;
import com.storagemanager.storage_management.exception.ResourceNotFoundException;
import com.storagemanager.storage_management.model.Client;
import com.storagemanager.storage_management.model.RentalAgreement;
import com.storagemanager.storage_management.repository.ClientRepository;
import com.storagemanager.storage_management.repository.RentalAgreementRepository;
import com.storagemanager.storage_management.security.UnitScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ClientService {

    private final ClientRepository clientRepository;
    private final RentalAgreementRepository rentalAgreementRepository;
    private final ClientDocumentService clientDocumentService;
    private final RentalAgreementService rentalAgreements;
    private final UnitScope unitScope;

    public List<Client> getAllClients() {
        return visibleOnly(clientRepository.findAll());
    }

    public Client getClientById(Long id) {
        Client client = clientRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Client not found with id: " + id));
        unitScope.requireClientVisible(client.getId());
        return client;
    }

    public List<Client> searchClients(String query) {
        if (query == null || query.trim().isEmpty()) {
            return getAllClients();
        }
        return visibleOnly(clientRepository.searchClients(query.trim()));
    }

    /** El ámbito de clientes lo resuelve {@link UnitScope}, que lo comparte con los documentos. */
    private List<Client> visibleOnly(List<Client> clients) {
        Set<Long> visible = unitScope.visibleClientIds();
        if (visible == null) return clients;
        return clients.stream().filter(client -> visible.contains(client.getId())).toList();
    }

    public List<ClientDTO> searchClientSummaries(String query) {
        // Activo es quien alquila algo hoy. Avalar no es alquilar: un fiador no
        // cuenta como cliente activo, pero tampoco es una ficha muerta, así que
        // se cuenta aparte lo que avala y se enseña en su fila.
        Map<Long, Long> activeRentalsByClient = new java.util.HashMap<>();
        Map<Long, Long> guaranteedByClient = new java.util.HashMap<>();
        for (var r : rentalAgreementRepository.findAllActiveRentals()) {
            r.guarantors().forEach(person -> guaranteedByClient.merge(person.getId(), 1L, Long::sum));
            // Cuenta a todos los arrendatarios. Los fiadores no: avalar no es
            // alquilar, y un fiador no debe salir en las cifras de ocupación.
            r.tenants().forEach(tenant -> activeRentalsByClient.merge(tenant.getId(), 1L, Long::sum));
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
                        .guaranteedRentalsCount(guaranteedByClient.getOrDefault(c.getId(), 0L))
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

        // Quien firma un contrato no se borra. Sin esto lo que salía no era un
        // mensaje sino un error 500 de clave ajena, y con los fiadores es fácil
        // de provocar: no alquilan nada, así que su ficha parece prescindible.
        List<RentalAgreement> signed = rentalAgreements.getAgreementsByClient(id);
        if (!signed.isEmpty()) {
            String numbers = signed.stream()
                    .map(RentalAgreement::getAgreementNumber)
                    .limit(3)
                    .collect(java.util.stream.Collectors.joining(", "));
            throw new BadRequestException(client.getFullName() + " firma "
                    + (signed.size() == 1 ? "el contrato " : signed.size() + " contratos (")
                    + numbers + (signed.size() == 1 ? "" : "...)")
                    + " y no se puede borrar. Si ya no alquila, finaliza sus contratos.");
        }
        // Los documentos archivados van con la ficha: sus filas apuntan al cliente
        // y sus ficheros al almacén, y ninguno de los dos debe sobrevivirle.
        clientDocumentService.deleteByClient(id);
        clientRepository.delete(client);
    }
}
