package com.storagemanager.storage_management;

import com.storagemanager.storage_management.config.DataSeeder;
import com.storagemanager.storage_management.dto.OwnerDTO;
import com.storagemanager.storage_management.dto.OwnerMembershipDTO;
import com.storagemanager.storage_management.dto.OwnerRequest;
import com.storagemanager.storage_management.dto.OwnershipDTO;
import com.storagemanager.storage_management.dto.OwnershipRequest;
import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.model.enums.OwnerType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class OwnerControllerTest {

    @Autowired
    private TestRestTemplate restTemplate;

    private OwnerDTO[] owners() {
        ResponseEntity<OwnerDTO[]> response = restTemplate.getForEntity("/api/owners", OwnerDTO[].class);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        return response.getBody();
    }

    private OwnerDTO ownerNamed(String name) {
        return Arrays.stream(owners())
                .filter(o -> name.equals(o.getFullName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Owner should be seeded: " + name));
    }

    private StorageUnit unitNumbered(String number) {
        ResponseEntity<StorageUnit[]> response = restTemplate.getForEntity("/api/storages", StorageUnit[].class);
        assertNotNull(response.getBody());
        return Arrays.stream(response.getBody())
                .filter(u -> number.equals(u.getUnitNumber()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Unit should be seeded: " + number));
    }

    private OwnershipDTO[] effectiveOwners(Long unitId) {
        ResponseEntity<OwnershipDTO[]> response = restTemplate.getForEntity("/api/storages/" + unitId + "/owners", OwnershipDTO[].class);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        return response.getBody();
    }

    private static BigDecimal pct(String fraction) {
        return DataSeeder.parseShare(fraction);
    }

    private static OwnerRequest person(String name) {
        OwnerRequest request = new OwnerRequest();
        request.setFullName(name);
        request.setType(OwnerType.PERSON);
        return request;
    }

    private OwnerDTO create(OwnerRequest request) {
        ResponseEntity<OwnerDTO> response = restTemplate.postForEntity("/api/owners", request, OwnerDTO.class);
        assertEquals(201, response.getStatusCode().value(), "Creating " + request.getFullName());
        assertNotNull(response.getBody());
        return response.getBody();
    }

    private int delete(String path) {
        return restTemplate.exchange(path, HttpMethod.DELETE, null, Map.class).getStatusCode().value();
    }

    @Test
    void parseShareHandlesFractionsAndPercentages() {
        assertEquals(new BigDecimal("16.6667"), DataSeeder.parseShare("1/6"));
        assertEquals(new BigDecimal("50.0000"), DataSeeder.parseShare("1/2"));
        assertEquals(new BigDecimal("100.0000"), DataSeeder.parseShare("1/1"));
        assertEquals(new BigDecimal("25.0000"), DataSeeder.parseShare(25));
        assertEquals(new BigDecimal("12.5000"), DataSeeder.parseShare("12.5 %"));
    }

    @Test
    void seededOwnersMembersAndShares() {
        assertTrue(owners().length >= 6, "Five persons and the comunidad de bienes are seeded");

        // The comunidad de bienes owns both locales outright and has four members adding up to 100 %
        OwnerDTO entity = ownerNamed(DataSeeder.ENTITY_NAME);
        assertEquals(OwnerType.COMUNIDAD_DE_BIENES, entity.getType());
        assertEquals(2, entity.getOwnerships().size());
        for (OwnershipDTO share : entity.getOwnerships()) {
            assertTrue(share.getStorageUnitNumber().matches("B[DT]"), share.getStorageUnitNumber());
            assertEquals(0, new BigDecimal("100").compareTo(share.getSharePercent()));
        }
        assertEquals(4, entity.getMembers().size());
        BigDecimal membersTotal = BigDecimal.ZERO;
        for (OwnerMembershipDTO m : entity.getMembers()) membersTotal = membersTotal.add(m.getSharePercent());
        assertTrue(new BigDecimal("100").subtract(membersTotal).abs().compareTo(new BigDecimal("0.001")) < 0, "Members: " + membersTotal);
        assertTrue(entity.getMembers().stream().anyMatch(m -> m.getMemberName().startsWith("Juan") && pct("1/2").compareTo(m.getSharePercent()) == 0));
        assertTrue(entity.getMembers().stream().noneMatch(m -> m.getMemberName().startsWith("Marta")));

        // Xiao: 1/2 of both flats directly, and 1/6 of the comunidad
        OwnerDTO xiao = ownerNamed("Xiao Varela Gómez");
        assertEquals(OwnerType.PERSON, xiao.getType());
        assertEquals(2, xiao.getOwnerships().size());
        for (OwnershipDTO share : xiao.getOwnerships()) {
            assertTrue(share.getStorageUnitNumber().matches("3[DE]"));
            assertEquals(0, pct("1/2").compareTo(share.getSharePercent()));
        }
        assertEquals(1, xiao.getMemberOf().size());
        assertEquals(DataSeeder.ENTITY_NAME, xiao.getMemberOf().get(0).getEntityName());
        assertEquals(0, pct("1/6").compareTo(xiao.getMemberOf().get(0).getSharePercent()));

        // Marta: only 1/4 of 3D, not a member of the comunidad
        OwnerDTO marta = ownerNamed("Marta Blanco Cuña");
        assertEquals(1, marta.getOwnerships().size());
        assertEquals("3D", marta.getOwnerships().get(0).getStorageUnitNumber());
        assertEquals(0, pct("1/4").compareTo(marta.getOwnerships().get(0).getSharePercent()));
        assertTrue(marta.getMemberOf().isEmpty());
    }

    @Test
    void effectiveOwnersOfUnitsFollowTheResolutionRule() {
        // A trastero has no shares of its own -> inherits the local's owner (the comunidad, 100 %)
        StorageUnit trastero = unitNumbered("1");
        OwnershipDTO[] inherited = effectiveOwners(trastero.getId());
        assertEquals(1, inherited.length);
        assertTrue(inherited[0].isInherited());
        assertEquals(DataSeeder.STORAGE_PREMISES_NUMBER, inherited[0].getInheritedFromUnitNumber());
        assertEquals(OwnerType.COMUNIDAD_DE_BIENES, inherited[0].getOwnerType());
        assertEquals(0, new BigDecimal("100").compareTo(inherited[0].getSharePercent()));

        // The flats have their own shares that add up to 100 %
        Map<String, Integer> ownersPerFlat = Map.of("3E", 2, "3D", 3, "1E", 1, "1D", 2);
        for (String number : ownersPerFlat.keySet()) {
            OwnershipDTO[] own = effectiveOwners(unitNumbered(number).getId());
            assertEquals(ownersPerFlat.get(number).intValue(), own.length, "Owners of " + number);
            BigDecimal total = BigDecimal.ZERO;
            for (OwnershipDTO share : own) {
                assertFalse(share.isInherited());
                assertEquals(OwnerType.PERSON, share.getOwnerType());
                total = total.add(share.getSharePercent());
            }
            assertEquals(0, new BigDecimal("100").compareTo(total), "Shares of " + number);
        }
    }

    @Test
    void ownerAndOwnershipCrudWithValidation() {
        OwnerDTO owner = create(person("Propietario CRUD"));
        Long ownerId = owner.getId();

        // Duplicate name (case-insensitive) is rejected
        assertEquals(400, restTemplate.postForEntity("/api/owners", person("propietario crud"), Map.class).getStatusCode().value());

        // A share needs a unit and a share within (0, 100]
        StorageUnit unit = unitNumbered("2");
        OwnershipRequest noUnit = new OwnershipRequest();
        noUnit.setOwnerId(ownerId);
        noUnit.setSharePercent(new BigDecimal("10"));
        assertEquals(400, restTemplate.postForEntity("/api/ownerships", noUnit, Map.class).getStatusCode().value());
        OwnershipRequest tooBig = new OwnershipRequest();
        tooBig.setOwnerId(ownerId);
        tooBig.setStorageUnitId(unit.getId());
        tooBig.setSharePercent(new BigDecimal("150"));
        assertEquals(400, restTemplate.postForEntity("/api/ownerships", tooBig, Map.class).getStatusCode().value());

        // Valid share on trastero 2: it now overrides the local's owner
        OwnershipRequest share = new OwnershipRequest();
        share.setOwnerId(ownerId);
        share.setStorageUnitId(unit.getId());
        share.setSharePercent(new BigDecimal("12.5"));
        ResponseEntity<OwnershipDTO> shareCreated = restTemplate.postForEntity("/api/ownerships", share, OwnershipDTO.class);
        assertEquals(201, shareCreated.getStatusCode().value());
        assertNotNull(shareCreated.getBody());
        assertEquals(0, new BigDecimal("12.5").compareTo(shareCreated.getBody().getSharePercent()));
        assertEquals(DataSeeder.STORAGE_PREMISES_NUMBER, shareCreated.getBody().getParentUnitNumber());
        assertEquals(400, restTemplate.postForEntity("/api/ownerships", share, Map.class).getStatusCode().value(), "Same owner + unit twice");

        OwnershipDTO[] effective = effectiveOwners(unit.getId());
        assertEquals(1, effective.length);
        assertFalse(effective[0].isInherited());
        assertEquals(ownerId, effective[0].getOwnerId());

        // Owner cannot be deleted while holding a share; update then remove the share and the owner
        assertEquals(400, delete("/api/owners/" + ownerId));
        share.setSharePercent(new BigDecimal("20"));
        ResponseEntity<OwnershipDTO> updated = restTemplate.exchange(
                "/api/ownerships/" + shareCreated.getBody().getId(), HttpMethod.PUT, new HttpEntity<>(share), OwnershipDTO.class);
        assertEquals(200, updated.getStatusCode().value());
        assertNotNull(updated.getBody());
        assertEquals(0, new BigDecimal("20").compareTo(updated.getBody().getSharePercent()));
        assertEquals(204, delete("/api/ownerships/" + shareCreated.getBody().getId()));
        assertEquals(204, delete("/api/owners/" + ownerId));
        assertEquals(404, restTemplate.getForEntity("/api/owners/" + ownerId, Map.class).getStatusCode().value());

        // Trastero 2 inherits the local's owner again
        OwnershipDTO[] back = effectiveOwners(unit.getId());
        assertEquals(1, back.length);
        assertTrue(back[0].isInherited());
    }

    @Test
    void entityMembersAreValidatedAndReplaced() {
        OwnerDTO personA = create(person("Miembro A"));
        OwnerDTO personB = create(person("Miembro B"));

        OwnerRequest entityRequest = new OwnerRequest();
        entityRequest.setFullName("Comunidad de prueba");
        entityRequest.setType(OwnerType.COMUNIDAD_DE_BIENES);
        OwnerRequest.MemberRequest a = new OwnerRequest.MemberRequest();
        a.setOwnerId(personA.getId());
        a.setSharePercent(new BigDecimal("60"));
        OwnerRequest.MemberRequest b = new OwnerRequest.MemberRequest();
        b.setOwnerId(personB.getId());
        b.setSharePercent(new BigDecimal("40"));
        entityRequest.setMembers(List.of(a, b));
        OwnerDTO entity = create(entityRequest);
        assertEquals(OwnerType.COMUNIDAD_DE_BIENES, entity.getType());
        assertEquals(2, entity.getMembers().size());
        assertEquals(0, new BigDecimal("60").compareTo(entity.getMembers().get(0).getSharePercent()));

        // Members must be persons, listed once, with a share within (0, 100]
        OwnerRequest.MemberRequest self = new OwnerRequest.MemberRequest();
        self.setOwnerId(entity.getId());
        self.setSharePercent(new BigDecimal("10"));
        entityRequest.setMembers(List.of(a, self));
        assertEquals(400, restTemplate.exchange("/api/owners/" + entity.getId(), HttpMethod.PUT, new HttpEntity<>(entityRequest), Map.class).getStatusCode().value());
        entityRequest.setMembers(List.of(a, a));
        assertEquals(400, restTemplate.exchange("/api/owners/" + entity.getId(), HttpMethod.PUT, new HttpEntity<>(entityRequest), Map.class).getStatusCode().value());
        OwnerRequest.MemberRequest tooBig = new OwnerRequest.MemberRequest();
        tooBig.setOwnerId(personA.getId());
        tooBig.setSharePercent(new BigDecimal("101"));
        entityRequest.setMembers(List.of(tooBig));
        assertEquals(400, restTemplate.exchange("/api/owners/" + entity.getId(), HttpMethod.PUT, new HttpEntity<>(entityRequest), Map.class).getStatusCode().value());

        // A member cannot be deleted while they belong to an entity
        assertEquals(400, delete("/api/owners/" + personA.getId()));

        // Replacing the member list drops B
        entityRequest.setMembers(List.of(a));
        ResponseEntity<OwnerDTO> replaced = restTemplate.exchange("/api/owners/" + entity.getId(), HttpMethod.PUT, new HttpEntity<>(entityRequest), OwnerDTO.class);
        assertEquals(200, replaced.getStatusCode().value());
        assertNotNull(replaced.getBody());
        assertEquals(1, replaced.getBody().getMembers().size());
        assertEquals(personA.getId(), replaced.getBody().getMembers().get(0).getMemberId());
        assertTrue(restTemplate.getForEntity("/api/owners/" + personB.getId(), OwnerDTO.class).getBody().getMemberOf().isEmpty());

        // Deleting the entity removes its memberships; then the persons can go
        assertEquals(204, delete("/api/owners/" + entity.getId()));
        assertEquals(204, delete("/api/owners/" + personA.getId()));
        assertEquals(204, delete("/api/owners/" + personB.getId()));
    }
}
