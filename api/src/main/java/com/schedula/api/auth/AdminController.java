package com.schedula.api.auth;

import com.schedula.persistence.AuditStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/v1/admin")
public class AdminController {

    private final ApiKeyService keys;
    private final AuditStore audit;

    public AdminController(ApiKeyService keys, AuditStore audit) {
        this.keys = keys;
        this.audit = audit;
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
        audit.append("api:admin", created.tenantId(), "TENANT_CREATED", "tenant",
                created.tenantId().toString(),
                "{\"name\":\"" + (req.name() == null ? "" : req.name()) + "\"}");
        // plaintext key returned exactly once; only its hash is stored
        return ResponseEntity.ok(Map.of(
                "tenantId", created.tenantId().toString(),
                "apiKey", created.apiKey()));
    }
}
