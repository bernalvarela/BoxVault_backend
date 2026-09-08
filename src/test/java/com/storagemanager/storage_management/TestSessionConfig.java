package com.storagemanager.storage_management;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * La sesión de los tests.
 * <p>
 * Desde que la API pide autenticación, un {@link TestRestTemplate} a pelo se
 * come un 401 en cada llamada. En vez de aflojar la seguridad para los tests
 * —que sería dejar de probar lo que corre de verdad—, esto entra una vez con el
 * administrador que crea SecurityBootstrap en el perfil de desarrollo y arrastra
 * sus cookies, más la cabecera antifalsificación que hace falta para escribir.
 * De paso, la autenticación queda probada en cada test.
 * <p>
 * Está en el paquete de la aplicación y sin más anotaciones que
 * {@code @Configuration}: así lo recoge el escaneo de cualquier
 * {@code @SpringBootTest} sin tener que tocar las clases de test.
 */
@Configuration
public class TestSessionConfig {

    /** El administrador del perfil "dev | h2" (ver application.yml). */
    private static final String USERNAME = "admin";
    private static final String PASSWORD = "admin-local-2026";

    /**
     * El {@link TestRestTemplate} lo crea Spring Boot por su cuenta, así que la
     * forma segura de alcanzarlo es cogerlo al vuelo cuando aparece.
     */
    @Bean
    static BeanPostProcessor authenticateTestRestTemplate() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof TestRestTemplate template) {
                    template.getRestTemplate().getInterceptors().add(new SessionInterceptor());
                }
                return bean;
            }
        };
    }

    /** Entra la primera vez que hace falta y reparte las cookies en las demás llamadas. */
    private static final class SessionInterceptor implements ClientHttpRequestInterceptor {

        private List<String> cookies;
        private String csrfToken;

        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
                throws IOException {
            if (cookies == null && !request.getURI().getPath().startsWith("/api/auth/")) {
                login(request.getURI());
            }
            if (cookies != null) {
                cookies.forEach(cookie -> request.getHeaders().add(HttpHeaders.COOKIE, cookie));
                if (csrfToken != null) {
                    request.getHeaders().add("X-XSRF-TOKEN", csrfToken);
                }
            }
            return execution.execute(request, body);
        }

        private synchronized void login(URI target) {
            if (cookies != null) return;

            URI loginUri = URI.create(target.getScheme() + "://" + target.getAuthority() + "/api/auth/login");
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String payload = "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(USERNAME, PASSWORD);

            // Sin interceptores: si no, esto se llamaría a sí mismo.
            ResponseEntity<String> response = new RestTemplate()
                    .postForEntity(loginUri, new HttpEntity<>(payload, headers), String.class);

            List<String> setCookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
            if (setCookies == null) {
                throw new IllegalStateException("El login de los tests no devolvió cookies de sesión");
            }
            List<String> collected = new ArrayList<>();
            for (String setCookie : setCookies) {
                String pair = setCookie.split(";", 2)[0];
                collected.add(pair);
                if (pair.startsWith("XSRF-TOKEN=")) {
                    csrfToken = pair.substring("XSRF-TOKEN=".length());
                }
            }
            cookies = collected;
        }
    }
}
