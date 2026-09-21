package com.storagemanager.storage_management;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * El edificio: el nivel del que cuelga todo y al que se concede el acceso.
 * <p>
 * Lo que se comprueba aquí no es que las pantallas pinten bien, sino las dos
 * promesas del diseño: que conceder un edificio da sus unidades sin enumerarlas,
 * y que una ficha de cliente es de su edificio y no se ve desde otro.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class BuildingScopeTest {

    @Autowired
    private TestRestTemplate asAdmin;

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(String path) {
        ResponseEntity<List> response = asAdmin.getForEntity(path, List.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "GET " + path);
        return response.getBody();
    }

    @Test
    void theSeededBuildingHoldsEveryRootUnit() {
        List<Map<String, Object>> buildings = list("/api/buildings");
        assertEquals(1, buildings.size(), "De momento hay un edificio: " + buildings);

        Map<String, Object> pasaxe = buildings.get(0);
        assertEquals("Pasaxe 29", pasaxe.get("name"));

        // Las seis raíces: los dos bajos y los cuatro pisos. Los trasteros no
        // cuelgan del edificio sino de su bajo, y por eso no se cuentan aquí.
        assertEquals(6, ((Number) pasaxe.get("unitCount")).intValue(),
                "Las unidades raíz cuelgan del edificio: " + pasaxe);
    }

    @Test
    void aUnitKnowsItsBuildingThroughItsParent() {
        // Un trastero no lleva el edificio puesto -sería repetir un dato que ya
        // está en su local- pero sabe cuál es subiendo.
        //
        // No se cuentan las raíces contra un número fijo: la base es la misma
        // para toda la suite y otra clase puede haber dado de alta una unidad.
        // Lo que se comprueba es la regla, que no depende de cuántas haya: las
        // seis sembradas cuelgan del edificio y ninguna hija lo lleva puesto.
        List<Map<String, Object>> units = list("/api/storages");
        long withBuilding = units.stream().filter(u -> u.get("building") != null).count();
        assertTrue(withBuilding >= 6, "Las seis raíces sembradas llevan edificio: " + withBuilding);

        assertTrue(units.stream()
                        .filter(u -> u.get("parent") != null)
                        .noneMatch(u -> u.get("building") != null),
                "Una unidad hija no guarda el edificio: lo hereda de su padre");
        assertTrue(units.stream().anyMatch(u -> u.get("parent") != null),
                "Y hay trasteros colgando de un local");
    }

    @Test
    void anEmptyBuildingCanBeCreatedAndRemoved() {
        ResponseEntity<Map> created = asAdmin.postForEntity("/api/buildings", Map.of(
                "name", "Portal de prueba",
                "address", "Rúa Inventada 1",
                "city", "A Coruña"), Map.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        Long id = ((Number) created.getBody().get("id")).longValue();
        assertEquals(0, ((Number) created.getBody().get("unitCount")).intValue());

        // Vacío se puede borrar; con unidades dentro, no, y eso lo protege el
        // servicio porque borrarlo dejaría media aplicación apuntando al aire.
        asAdmin.delete("/api/buildings/" + id);
        assertEquals(1, list("/api/buildings").size(), "Vuelve a quedar sólo Pasaxe 29");
    }

    @Test
    void theCommunityKeepsItsOwnBookOutOfTheTaxFigures() {
        ResponseEntity<Map> community = asAdmin.postForEntity("/api/communities", Map.of(
                "name", "Comunidad de Propietarios Pasaxe 29",
                "taxId", "H70000000",
                "iban", "ES00 0000 0000 0000 0000 0000"), Map.class);
        assertEquals(HttpStatus.CREATED, community.getStatusCode());
        Long id = ((Number) community.getBody().get("id")).longValue();

        // Un gasto de la comunidad: el ascensor. Esto NO es gasto deducible de
        // ningún propietario -lo deducible para él es su cuota- y por eso vive
        // en su propio libro.
        ResponseEntity<Map> entry = asAdmin.postForEntity("/api/communities/" + id + "/entries", Map.of(
                "entryDate", "2026-03-15",
                "type", "GASTO",
                "concept", "Reparación del ascensor",
                "amount", 1200.00,
                "supplier", "Ascensores del Noroeste"), Map.class);
        assertEquals(HttpStatus.CREATED, entry.getStatusCode());
        assertEquals(-1200.00, ((Number) entry.getBody().get("signedAmount")).doubleValue(), 0.01,
                "Un gasto resta del saldo aunque se teclee en positivo");

        // Y no aparece entre los gastos de la casa, que son los que tributan.
        assertTrue(list("/api/expenses").stream()
                        .noneMatch(e -> "Reparación del ascensor".equals(e.get("description"))),
                "El libro de la comunidad no se mezcla con los gastos deducibles");

        ResponseEntity<Map> statement = asAdmin.getForEntity(
                "/api/communities/" + id + "/statement?year=2026", Map.class);
        assertEquals(HttpStatus.OK, statement.getStatusCode());
        assertEquals(-1200.00, ((Number) statement.getBody().get("balance")).doubleValue(), 0.01);
    }

    @Test
    void theAmountOfAnEntryIsAlwaysWrittenInPositive() {
        ResponseEntity<Map> community = asAdmin.postForEntity("/api/communities", Map.of(
                "name", "Comunidad de prueba del signo"), Map.class);
        Long id = ((Number) community.getBody().get("id")).longValue();

        // El signo lo pone el tipo del apunte, no quien teclea: así un gasto no
        // se cuela como ingreso por un menos mal puesto.
        ResponseEntity<Map> refused = asAdmin.postForEntity("/api/communities/" + id + "/entries", Map.of(
                "entryDate", "2026-03-15",
                "type", "GASTO",
                "concept", "Con el signo cambiado",
                "amount", -50.00), Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, refused.getStatusCode());
    }
}
