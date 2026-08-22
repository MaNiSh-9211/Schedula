package com.schedula.api.auth;

import com.schedula.persistence.AuditStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/admin")
public class AdminController {

    private final ApiKeyService keys;
    private final AuditStore auditStore;

    public AdminController(ApiKeyService keys, AuditStore auditStore) {
        this.keys = keys;
        this.auditStore = auditStore;
    }

    public record CreateTenantRequest(String name) {
    }

    /** Platform-scope only: mints a tenant + its one-time plaintext API key. */
    @PostMapping("/tenants")
    ResponseEntity<Map<String, Object>> createTenant(@RequestBody CreateTenantRequest req,
                                                     HttpServletRequest http) {
        if (!RequestTenant.isAdmin(http)) {
            return ResponseEntity.status(403).body(Map.of(
                    "title", "Forbidden",
                    "detail", "admin key required"));
        }
        var created = keys.createTenant(req.name() == null ? "unnamed" : req.name());
        auditStore.append("api:admin", created.tenantId(), "TENANT_CREATED", "tenant",
                created.tenantId().toString(),
                "{\"name\":\"" + (req.name() == null ? "" : req.name()) + "\"}");
        return ResponseEntity.ok(Map.of(
                "tenantId", created.tenantId().toString(),
                "apiKey", created.apiKey()));
    }

    @PostMapping("/tenants/{id}/rotate")
    ResponseEntity<?> rotate(@PathVariable UUID id, HttpServletRequest http) {
        if (!RequestTenant.isAdmin(http)) {
            return ResponseEntity.status(403).body(Map.of("detail", "admin key required"));
        }
        var rotated = keys.rotate(id);
        auditStore.append("api:admin", id, "API_KEY_ROTATED", "tenant", id.toString(), null);
        return ResponseEntity.ok(Map.of("tenantId", id.toString(),
                "apiKey", rotated.apiKey()));
    }

    /** Audit trail viewer (enterprise parity): newest first, admin scope. */
    @GetMapping("/audits")
    ResponseEntity<?> audits(@RequestParam(defaultValue = "100") int limit,
                             HttpServletRequest http) {
        if (!RequestTenant.isAdmin(http)) {
            return ResponseEntity.status(403).body(Map.of("detail", "admin key required"));
        }
        List<Map<String, Object>> rows = auditStore.recent(Math.min(limit, 500));
        return ResponseEntity.ok(rows);
    }
}

