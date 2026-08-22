package com.schedula.api.auth;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Optional;
import java.util.UUID;

public final class RequestTenant {

    private RequestTenant() {
    }

    public static Optional<UUID> tenant(HttpServletRequest request) {
        Object v = request.getAttribute(ApiKeyAuthFilter.ATTR_TENANT);
        return v instanceof UUID id ? Optional.of(id) : Optional.empty();
    }

    public static boolean isAdmin(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(ApiKeyAuthFilter.ATTR_ADMIN));
    }
}
