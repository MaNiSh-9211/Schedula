package com.schedula.api.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Authentication boundary (SECURITY.md). /v1/** requires X-API-Key (tenant scope) or
 * X-Admin-Key (platform scope, for admin endpoints and cross-tenant access).
 * Actuator/health/metrics stay unauthenticated for probes and the metrics scraper.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String ATTR_TENANT = "schedula.tenantId";
    public static final String ATTR_ADMIN = "schedula.isAdmin";

    private final ApiKeyService keys;
    private final boolean enabled;

    public ApiKeyAuthFilter(ApiKeyService keys,
                            @Value("${schedula.auth.enabled:true}") boolean enabled) {
        this.keys = keys;
        this.enabled = enabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String apiKey = header(request, "X-API-Key");
        String adminKey = header(request, "X-Admin-Key");

        if (keys.isAdminKey(adminKey)) {
            request.setAttribute(ATTR_ADMIN, true);
            chain.doFilter(request, response);
            return;
        }

        Optional<UUID> tenant = keys.authenticate(apiKey);
        if (tenant.isPresent()) {
            request.setAttribute(ATTR_TENANT, tenant.get());
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/problem+json");
        response.getWriter().write("""
                {"type":"about:blank","title":"Unauthorized","status":401,
                 "detail":"provide a valid X-API-Key"}
                """);
    }

    private static String header(HttpServletRequest req, String name) {
        String v = req.getHeader(name);
        if (v != null && !v.isBlank()) return v.trim();
        String auth = req.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) return auth.substring(7).trim();
        return null;
    }
}
