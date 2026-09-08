package com.storagemanager.storage_management;

import com.storagemanager.storage_management.model.StorageUnit;
import com.storagemanager.storage_management.service.StorageUnitService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * El ámbito por unidades: un usuario al que se le concede un local ve ese local
 * y lo que cuelga de él, y nada más —ni unidades, ni clientes, ni alquileres, ni
 * cobros, ni gastos, ni totales de los demás—.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
// Una sola instancia para toda la clase: el usuario con ámbito se crea una vez
// (no se pueden borrar usuarios, así que crearlo por test daría "ya existe").
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UnitScopeTest {

    @Autowired
    private TestRestTemplate asAdmin;

    @Autowired
    private StorageUnitService storageUnitService;

    @LocalServerPort
    private int port;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    /** Un local de los sembrados y uno de sus trasteros. */
    private StorageUnit localWithChildren;
    private Session scoped;

    @BeforeAll
    void createScopedUser() {
        if (scoped != null) return;

        List<StorageUnit> units = storageUnitService.getAllUnits();
        localWithChildren = units.stream()
                .filter(u -> units.stream().anyMatch(child ->
                        child.getParent() != null && child.getParent().getId().equals(u.getId())))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Debería haber un local con trasteros dentro"));

        Long gestorRoleId = roleIdOf("Gestor");
        String username = "ambito-test";
        String password = "ambito-test-2026";

        ResponseEntity<Map> created = asAdmin.postForEntity(url("/api/users"), Map.of(
                "username", username,
                "fullName", "Usuario con ámbito",
                "password", password,
                "roleId", gestorRoleId,
                // Lo que se está probando: NO ve todas las unidades, sólo la concedida.
                "fullScope", false,
                "active", true,
                "unitScopeIds", List.of(localWithChildren.getId())), Map.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());

        scoped = login(username, password);
    }

    @Test
    void veSuLocalYLoQueCuelgaDeEl() {
        List<Map<String, Object>> visibles = scoped.getList("/api/storages");
        List<StorageUnit> todas = storageUnitService.getAllUnits();

        assertTrue(visibles.size() < todas.size(), "No puede ver todas las unidades");
        assertTrue(visibles.stream().anyMatch(u -> idOf(u).equals(localWithChildren.getId())),
                "Ve el local que se le concedió");

        // Todos los trasteros de dentro, sin haberlos concedido uno a uno
        long hijosSembrados = todas.stream()
                .filter(u -> u.getParent() != null && u.getParent().getId().equals(localWithChildren.getId()))
                .count();
        long hijosVisibles = visibles.stream()
                .filter(u -> {
                    Map<?, ?> parent = (Map<?, ?>) u.get("parent");
                    return parent != null && localWithChildren.getId().equals(((Number) parent.get("id")).longValue());
                })
                .count();
        assertEquals(hijosSembrados, hijosVisibles, "La concesión de un local incluye todo su subárbol");
    }

    @Test
    void noAbreUnaUnidadDeFuera() {
        StorageUnit fuera = storageUnitService.getAllUnits().stream()
                .filter(u -> !u.getId().equals(localWithChildren.getId()))
                .filter(u -> u.getParent() == null
                        || !u.getParent().getId().equals(localWithChildren.getId()))
                .findFirst()
                .orElseThrow();

        assertEquals(HttpStatus.FORBIDDEN, scoped.get("/api/storages/" + fuera.getId()).getStatusCode(),
                "Pedir una unidad de fuera del ámbito da 403");

        // Un listado no falla, filtra: pedir los gastos de esa unidad devuelve
        // 200 y ninguno, que es lo que ese usuario tiene que ver.
        assertEquals(0, scoped.getList("/api/expenses?storageUnitId=" + fuera.getId()).size(),
                "Los gastos de una unidad de fuera no se enseñan");
    }

    @Test
    void loQueCuelgaDeSusUnidadesSeFiltraSolo() {
        // Alquileres, cobros y gastos: menos que los que ve el administrador
        assertTrue(scoped.getList("/api/rentals").size() < asAdminList("/api/rentals").size(),
                "Sólo los alquileres de sus unidades");
        assertTrue(scoped.getList("/api/expenses").size() < asAdminList("/api/expenses").size(),
                "Sólo los gastos de sus unidades");
        assertTrue(scoped.getList("/api/clients").size() < asAdminList("/api/clients").size(),
                "Sólo los clientes que alquilan en sus unidades");

        // Y los totales del panel también
        ResponseEntity<Map> panel = scoped.getMap("/api/statistics/dashboard");
        assertEquals(HttpStatus.OK, panel.getStatusCode());
        ResponseEntity<Map> panelAdmin = asAdmin.getForEntity(url("/api/statistics/dashboard"), Map.class);
        assertTrue(((Number) panel.getBody().get("totalUnits")).intValue()
                        < ((Number) panelAdmin.getBody().get("totalUnits")).intValue(),
                "El panel cuenta sólo sus unidades");
    }

    @Test
    void sinConcesionesNoVeNada() {
        String username = "sin-ambito-test";
        String password = "sin-ambito-test-2026";
        ResponseEntity<Map> created = asAdmin.postForEntity(url("/api/users"), Map.of(
                "username", username,
                "fullName", "Usuario sin ámbito",
                "password", password,
                "roleId", roleIdOf("Gestor"),
                "fullScope", false,
                "active", true,
                "unitScopeIds", List.of()), Map.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());

        Session sinAmbito = login(username, password);
        assertEquals(0, sinAmbito.getList("/api/storages").size(), "Sin concesiones no ve ninguna unidad");
        assertEquals(0, sinAmbito.getList("/api/rentals").size());
        assertEquals(0, sinAmbito.getList("/api/expenses").size());
    }

    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asAdminList(String path) {
        return asAdmin.getForEntity(url(path), List.class).getBody();
    }

    private static Long idOf(Map<String, Object> row) {
        return ((Number) row.get("id")).longValue();
    }

    private Long roleIdOf(String name) {
        for (Object row : asAdmin.getForEntity(url("/api/users/roles"), List.class).getBody()) {
            Map<?, ?> role = (Map<?, ?>) row;
            if (name.equals(role.get("name"))) return ((Number) role.get("id")).longValue();
        }
        throw new AssertionError("Debería existir el perfil " + name);
    }

    private Session login(String username, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = new RestTemplate().postForEntity(
                url("/api/auth/login"),
                new HttpEntity<>(Map.of("username", username, "password", password), headers),
                String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        List<String> cookies = new ArrayList<>();
        for (String setCookie : response.getHeaders().getOrDefault(HttpHeaders.SET_COOKIE, List.of())) {
            cookies.add(setCookie.split(";", 2)[0]);
        }
        return new Session(cookies);
    }

    /** La sesión del usuario con ámbito. */
    private final class Session {
        private final HttpHeaders headers = new HttpHeaders();
        private final RestTemplate client = new RestTemplate();

        Session(List<String> cookies) {
            cookies.forEach(cookie -> headers.add(HttpHeaders.COOKIE, cookie));
            headers.setContentType(MediaType.APPLICATION_JSON);
            client.setErrorHandler(new NoErrorHandler());
        }

        ResponseEntity<String> get(String path) {
            return client.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> getList(String path) {
            ResponseEntity<List> response =
                    client.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), List.class);
            assertEquals(HttpStatus.OK, response.getStatusCode(), "GET " + path);
            return response.getBody();
        }

        @SuppressWarnings("unchecked")
        ResponseEntity<Map> getMap(String path) {
            return client.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        }
    }

    private static final class NoErrorHandler extends org.springframework.web.client.DefaultResponseErrorHandler {
        @Override
        public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
            return false;
        }
    }
}
