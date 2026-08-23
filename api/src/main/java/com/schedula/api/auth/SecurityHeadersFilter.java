package com.schedula.api.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Security headers on every response (defense-in-depth even behind an API gateway).
 * CORS is handled by the gateway; this adds browser-facing headers the UI needs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("X-Frame-Options", "DENY");
        res.setHeader("Cache-Control", "no-store");
        if (req.getRequestURI().startsWith("/v1/") || req.getRequestURI().equals("/") ||
            req.getRequestURI().startsWith("/app.js") || req.getRequestURI().startsWith("/style.css")) {
            res.setHeader("Content-Security-Policy",
                    "default-src 'self'; script-src 'self'; style-src 'self'");
        }
        chain.doFilter(req, res);
    }
}
