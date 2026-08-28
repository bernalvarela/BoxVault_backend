package com.storagemanager.storage_management;

import com.storagemanager.storage_management.config.DataSeeder;
import com.storagemanager.storage_management.dto.OwnerDTO;
import com.storagemanager.storage_management.dto.OwnerRequest;
import com.storagemanager.storage_management.dto.OwnershipDTO;
import com.storagemanager.storage_management.dto.OwnershipRequest;
import com.storagemanager.storage_management.dto.StorageGroupDTO;
import com.storagemanager.storage_management.model.StorageUnit;
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

    private StorageGroupDTO groupNamed(String name) {
        ResponseEntity<StorageGroupDTO[]> response = restTemplate.getForEntity("/api/storage-groups", StorageGroupDTO[].class);
        assertNotNull(response.getBody());
        return Arrays.stream(response.getBody())
                .filter(g -> name.equals(g.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Group should be seeded: " + name));
    }

    private static BigDecimal pct(String fraction) {
        return DataSeeder.parseShare(fraction);
    }

    @Test
    void parseShareHandlesFractionsAndPercentages() {
        assertEquals(new BigDecimal("16.6667"), DataSeeder.parseShare("1/6"));
        assertEquals(new BigDecimal("50.0000"), DataSeeder.parseShare("1/2"));
        assertEquals(new BigDecimal("33.3333"), DataSeeder.parseShare("1/3"));
        assertEquals(new BigDecimal("25.0000"), DataSeeder.parseShare(25));
        assertEquals(new BigDecimal("12.5000"), DataSeeder.parseShare("12.5 %"));
    }

    @Test
    void seededOwnersAndTheirShares() {
        assertTrue(owners().length >= 5, "The five owners are seeded");

        OwnerDTO xiao = ownerNamed("Xiao Varela Gómez");
        assertNotNull(xiao.getOwnerships());
        // 1/6 of both "baixos" groups plus 1/2 of each apartment
        assertEquals(4, xiao.getOwnerships().size());
        long groupShares = xiao.getOwnerships().stream().filter(s -> s.getStorageUnitId() == null).count();
        assertEquals(2, groupShares);
        for (OwnershipDTO share : xiao.getOwnerships()) {
            BigDecimal expected = share.getStorageUnitId() == null ? pct("1/6") : pct("1/2");
            assertEquals(0, expected.compareTo(share.getSharePercent()), "Share of Xiao in " + share);
        }

        // Marta: only 1/4 of 3D
        OwnerDTO marta = ownerNamed("Marta Blanco Cuña");
        assertEquals(1, marta.getOwnerships().size());
        assertEquals("3D", marta.getOwnerships().get(0).getStorageUnitNumber());
        assertEquals(0, pct("1/4").compareTo(marta.getOwnerships().get(0).getSharePercent()));

        // Bernal: 1/6 of the baixos, 1/2 of 3E, 1/4 of 3D
        OwnerDTO bernal = ownerNamed("Bernal Varela Gómez");
        OwnershipDTO in3d = bernal.getOwnerships().stream()
                .filter(s -> "3D".equals(s.getStorageUnitNumber())).findFirst().orElseThrow();
        assertEquals(0, pct("1/4").compareTo(in3d.getSharePercent()));

        // Baixos: Juan 1/2 (1/3 + 1/6), Marisé 1/6, Xiao 1/6, Bernal 1/6 -> 100 %
        OwnerDTO juan = ownerNamed("Juan María Varela Brage");
        OwnerDTO marise = ownerNamed("María Luisa Gómez Gómez");
        assertEquals(2, juan.getOwnerships().size());
        assertEquals(2, marise.getOwnerships().size());
        juan.getOwnerships().forEach(s -> assertEquals(0, pct("1/2").compareTo(s.getSharePercent())));
        marise.getOwnerships().forEach(s -> assertEquals(0, pct("1/6").compareTo(s.getSharePercent())));
    }

    @Test
    void effectiveOwnersOfUnitsFollowTheResolutionRule() {
        // A trastero has no shares of its own -> inherits the group split (4 owners, 100 %)
        StorageUnit trastero = unitNumbered("1");
        ResponseEntity<OwnershipDTO[]> inherited = restTemplate.getForEntity(
                "/api/storages/" + trastero.getId() + "/owners", OwnershipDTO[].class);
        assertEquals(200, inherited.getStatusCode().value());
        assertNotNull(inherited.getBody());
        assertEquals(4, inherited.getBody().length);
        BigDecimal groupTotal = BigDecimal.ZERO;
        for (OwnershipDTO share : inherited.getBody()) {
            assertTrue(share.isInherited());
            assertEquals(trastero.getStorageGroup().getId(), share.getStorageGroupId());
            groupTotal = groupTotal.add(share.getSharePercent());
        }
        // 50 + 16.6667 * 3 = 100.0001 because of rounding to four decimals
        assertTrue(new BigDecimal("100").subtract(groupTotal).abs().compareTo(new BigDecimal("0.001")) < 0, "Baixos split adds up to 100 %: " + groupTotal);

        // The apartments have their own shares -> they override the group ones and add up to 100 %
        for (String number : new String[]{"3E", "3D"}) {
            StorageUnit apartment = unitNumbered(number);
            ResponseEntity<OwnershipDTO[]> own = restTemplate.getForEntity(
                    "/api/storages/" + apartment.getId() + "/owners", OwnershipDTO[].class);
            assertNotNull(own.getBody());
            assertEquals("3E".equals(number) ? 2 : 3, own.getBody().length);
            BigDecimal total = BigDecimal.ZERO;
            for (OwnershipDTO share : own.getBody()) {
                assertFalse(share.isInherited());
                assertEquals(apartment.getId(), share.getStorageUnitId());
                total = total.add(share.getSharePercent());
            }
            assertEquals(0, new BigDecimal("100").compareTo(total), "Shares of " + number);
        }
    }

    @Test
    void ownerAndOwnershipCrudWithValidation() {
        OwnerRequest request = new OwnerRequest();
        request.setFullName("Propietario CRUD");
        request.setEmail("crud@propietarios.example.com");
        ResponseEntity<OwnerDTO> created = restTemplate.postForEntity("/api/owners", request, OwnerDTO.class);
        assertEquals(201, created.getStatusCode().value());
        assertNotNull(created.getBody());
        Long ownerId = created.getBody().getId();

        // Duplicate name (case-insensitive) is rejected
        OwnerRequest duplicate = new OwnerRequest();
        duplicate.setFullName("propietario crud");
        assertEquals(400, restTemplate.postForEntity("/api/owners", duplicate, Map.class).getStatusCode().value());

        // A share needs exactly one target
        StorageGroupDTO group = groupNamed(DataSeeder.DEFAULT_GROUP_NAME);
        StorageUnit unit = unitNumbered("2");
        OwnershipRequest both = new OwnershipRequest();
        both.setOwnerId(ownerId);
        both.setStorageGroupId(group.getId());
        both.setStorageUnitId(unit.getId());
        both.setSharePercent(new BigDecimal("10"));
        assertEquals(400, restTemplate.postForEntity("/api/ownerships", both, Map.class).getStatusCode().value());

        // Share above 100 % is rejected by validation
        OwnershipRequest tooBig = new OwnershipRequest();
        tooBig.setOwnerId(ownerId);
        tooBig.setStorageUnitId(unit.getId());
        tooBig.setSharePercent(new BigDecimal("150"));
        assertEquals(400, restTemplate.postForEntity("/api/ownerships", tooBig, Map.class).getStatusCode().value());

        // Valid unit-level share
        OwnershipRequest share = new OwnershipRequest();
        share.setOwnerId(ownerId);
        share.setStorageUnitId(unit.getId());
        share.setSharePercent(new BigDecimal("12.5"));
        ResponseEntity<OwnershipDTO> shareCreated = restTemplate.postForEntity("/api/ownerships", share, OwnershipDTO.class);
        assertEquals(201, shareCreated.getStatusCode().value());
        assertNotNull(shareCreated.getBody());
        assertEquals(0, new BigDecimal("12.5").compareTo(shareCreated.getBody().getSharePercent()));
        assertEquals(group.getId(), shareCreated.getBody().getStorageGroupId(), "Unit-level shares report the unit's group");

        // Same owner + same unit twice is rejected
        assertEquals(400, restTemplate.postForEntity("/api/ownerships", share, Map.class).getStatusCode().value());

        // The unit now has its own shares, so it no longer inherits the group ones
        ResponseEntity<OwnershipDTO[]> effective = restTemplate.getForEntity(
                "/api/storages/" + unit.getId() + "/owners", OwnershipDTO[].class);
        assertNotNull(effective.getBody());
        assertEquals(1, effective.getBody().length);
        assertFalse(effective.getBody()[0].isInherited());

        // Owner cannot be deleted while holding a share
        assertEquals(400, restTemplate.exchange("/api/owners/" + ownerId, HttpMethod.DELETE, null, Map.class)
                .getStatusCode().value());

        // Update the share, then remove it and the owner
        share.setSharePercent(new BigDecimal("20"));
        ResponseEntity<OwnershipDTO> updated = restTemplate.exchange(
                "/api/ownerships/" + shareCreated.getBody().getId(), HttpMethod.PUT, new HttpEntity<>(share), OwnershipDTO.class);
        assertEquals(200, updated.getStatusCode().value());
        assertNotNull(updated.getBody());
        assertEquals(0, new BigDecimal("20").compareTo(updated.getBody().getSharePercent()));

        assertEquals(204, restTemplate.exchange("/api/ownerships/" + shareCreated.getBody().getId(), HttpMethod.DELETE, null, Void.class)
                .getStatusCode().value());
        assertEquals(204, restTemplate.exchange("/api/owners/" + ownerId, HttpMethod.DELETE, null, Void.class)
                .getStatusCode().value());
        assertEquals(404, restTemplate.getForEntity("/api/owners/" + ownerId, Map.class).getStatusCode().value());

        // Unit "2" inherits the group split again
        ResponseEntity<OwnershipDTO[]> back = restTemplate.getForEntity(
                "/api/storages/" + unit.getId() + "/owners", OwnershipDTO[].class);
        assertNotNull(back.getBody());
        assertEquals(4, back.getBody().length);
    }
}
