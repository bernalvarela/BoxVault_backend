package com.storagemanager.storage_management.dto;

import com.storagemanager.storage_management.model.Client;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Una persona nombrada dentro de un informe: quién es y su NIF.
 * <p>
 * Existe porque un contrato ya no tiene "el inquilino y, si acaso, el segundo":
 * tiene los arrendatarios que haga falta. Donde antes había cuatro campos
 * sueltos -nombre, NIF, nombre del segundo, NIF del segundo- ahora hay una
 * lista, que es lo que de verdad describe a las partes de un contrato.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PersonDTO {

    private Long id;
    private String name;
    /** DNI / NIE / NIF, cuando consta. */
    private String documentId;

    public static PersonDTO of(Client client) {
        return PersonDTO.builder()
                .id(client.getId())
                .name(client.getFullName())
                .documentId(client.getDocumentId())
                .build();
    }

    public static List<PersonDTO> of(List<Client> clients) {
        return clients.stream().map(PersonDTO::of).toList();
    }
}
