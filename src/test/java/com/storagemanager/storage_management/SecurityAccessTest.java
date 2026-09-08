package com.storagemanager.storage_management;

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
 * Que la puerta esté echada: sin sesión no se entra, y con sesión sólo se llega
 * a lo que dan los permisos. Los demás tests dan por hecho lo primero (entran
 * con el administrador, ver TestSessionConfig); esto lo comprueba.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class SecurityAccessTest {

    @Autowired
    private TestRestTemplate asAdmin;

    @LocalServerPort
    private int port;

    /** Un cliente sin sesión: ni cookies ni cabeceras. */
    private final RestTemplate anonymous = new RestTemplate(new org.springframework.http.client.SimpleClientHttpRequestFactory());

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void sinSesionNoSeEntra() {
        anonymous.setErrorHandler(new NoErrorHandler());
        ResponseEntity<String> response = anonymous.getForEntity(url("/api/clients"), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode(), "Sin cookie de sesión hay que dar 401");
    }

    @Test
    void unUsuarioDeConsultaNoLlegaALoQueNoTiene() {
        // Un usuario con el perfil "Consulta": lee clientes, no toca impuestos.
        Long consultaRoleId = roleIdOf("Consulta");
        String username = "consulta-test";
        String password = "consulta-test-2026";

        ResponseEntity<Map> created = asAdmin.postForEntity(url("/api/users"), Map.of(
                "username", username,
                "fullName", "Usuario de prueba",
                "password", password,
                "roleId", consultaRoleId,
                "fullScope", true,
                "active", true), Map.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());

        Session session = login(username, password);

        assertEquals(HttpStatus.OK, session.get("/api/clients").getStatusCode(),
                "Consulta tiene LEER en clientes");
        assertEquals(HttpStatus.FORBIDDEN, session.get("/api/taxes/filings").getStatusCode(),
                "Consulta no tiene nada en impuestos: 403");
        assertEquals(HttpStatus.FORBIDDEN, session.get("/api/users").getStatusCode(),
                "Sólo quien administra usuarios ve la lista de usuarios");
        assertEquals(HttpStatus.FORBIDDEN, session.post("/api/clients", Map.of(
                        "fullName", "No debería crearse",
                        "email", "no@ejemplo.com",
                        "phone", "600000000")).getStatusCode(),
                "Consulta es de sólo lectura: no puede crear clientes");
    }

    private Long roleIdOf(String name) {
        ResponseEntity<List> roles = asAdmin.getForEntity(url("/api/users/roles"), List.class);
        assertEquals(HttpStatus.OK, roles.getStatusCode());
        for (Object row : roles.getBody()) {
            Map<?, ?> role = (Map<?, ?>) row;
            if (name.equals(role.get("name"))) {
                return ((Number) role.get("id")).longValue();
            }
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
        assertEquals(HttpStatus.OK, response.getStatusCode(), "El usuario recién creado debería poder entrar");

        List<String> cookies = new ArrayList<>();
        String csrf = null;
        for (String setCookie : response.getHeaders().getOrDefault(HttpHeaders.SET_COOKIE, List.of())) {
            String pair = setCookie.split(";", 2)[0];
            cookies.add(pair);
            if (pair.startsWith("XSRF-TOKEN=")) csrf = pair.substring("XSRF-TOKEN=".length());
        }
        return new Session(cookies, csrf);
    }

    /** La sesión de otro usuario, con sus cookies a cuestas. */
    private final class Session {
        private final HttpHeaders headers = new HttpHeaders();
        private final RestTemplate client = new RestTemplate();

        Session(List<String> cookies, String csrfToken) {
            cookies.forEach(cookie -> headers.add(HttpHeaders.COOKIE, cookie));
            if (csrfToken != null) headers.add("X-XSRF-TOKEN", csrfToken);
            headers.setContentType(MediaType.APPLICATION_JSON);
            client.setErrorHandler(new NoErrorHandler());
        }

        ResponseEntity<String> get(String path) {
            return client.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
        }

        ResponseEntity<String> post(String path, Object body) {
            return client.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        }
    }

    /** Para poder mirar el código de estado en vez de que salte una excepción. */
    private static final class NoErrorHandler extends org.springframework.web.client.DefaultResponseErrorHandler {
        @Override
        public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
            return false;
        }
    }
}
