package com.schedula.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AuthN boundary: /v1/** requires a valid tenant API key or the admin key; tenant scope
 * is forced from the key; admin endpoints mint tenants and are platform-scope only.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "schedula.roles.scheduler=false",
        "schedula.roles.worker=false",
        "schedula.auth.enabled=true",
        "schedula.auth.admin-key=admin-master-key-it",
        "schedula.auth.default-tenant-key=sk_00000000-0000-0000-0000-000000000001_defaultsecret"
})
class AuthIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    TestRestTemplate http;

    private HttpHeaders withKey(String key) {
        HttpHeaders h = new HttpHeaders();
        if (key != null) h.set("X-API-Key", key);
        return h;
    }

    private ResponseEntity<String> submit(HttpHeaders headers, String body) {
        return http.postForEntity("/v1/jobs", new HttpEntity<>(jsonBody(body), headers), String.class);
    }

    private static Object jsonBody(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void missingOrInvalidKeyIsRejected() {
        assertThat(submit(withKey(null),
                "{\"jobType\":\"log\",\"payload\":{}}").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(submit(withKey("sk_00000000-0000-0000-0000-000000000001_wrong"),
                "{\"jobType\":\"log\",\"payload\":{}}").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void validTenantKeyIsAcceptedAndScopeForced() {
        // body claims another tenant; the KEY must win
        ResponseEntity<String> res = submit(
                withKey("sk_00000000-0000-0000-0000-000000000001_defaultsecret"),
                "{\"jobType\":\"log\",\"payload\":{},\"tenantId\":\"11111111-1111-1111-1111-111111111111\"}");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody()).contains("00000000-0000-0000-0000-000000000001");
    }

    private HttpHeaders adminHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Admin-Key", "admin-master-key-it");
        return h;
    }

    @Test
    void adminMintsTenantWhoseKeyWorksImmediately() {
        var req = new HttpEntity<>(jsonBody("{\"name\":\"acme\"}"), adminHeaders());
        ResponseEntity<String> created = http.postForEntity("/v1/admin/tenants", req, String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(created.getBody()).contains("apiKey");

        String apiKey = created.getBody().replaceAll(".*\"apiKey\":\"([^\"]+)\".*", "$1");

        ResponseEntity<String> use = submit(withKey(apiKey),
                "{\"jobType\":\"log\",\"payload\":{}}");
        assertThat(use.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // non-admin cannot mint tenants even with a valid tenant key
        ResponseEntity<String> forbidden = http.postForEntity("/v1/admin/tenants",
                new HttpEntity<>(jsonBody("{\"name\":\"x\"}"), withKey(apiKey)), String.class);
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // admin key keeps working for platform-scope calls
        ResponseEntity<String> another =
                http.postForEntity("/v1/admin/tenants",
                        new HttpEntity<>(jsonBody("{\"name\":\"second\"}"), adminHeaders()),
                        String.class);
        assertThat(another.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
