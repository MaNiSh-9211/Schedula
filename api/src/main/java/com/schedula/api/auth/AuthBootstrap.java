package com.schedula.api.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

/**
 * Seeds a credential for the default tenant on first boot so the platform is usable
 * immediately: uses SCHEDULA_DEFAULT_API_KEY when provided, otherwise generates one
 * and logs it once with a change-me warning.
 */
@Configuration
public class AuthBootstrap {

    public static final UUID DEFAULT_TENANT =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Bean
    ApplicationRunner seedDefaultTenantKey(ApiKeyService keys,
                                           @Value("${schedula.auth.default-tenant-key:}") String providedKey,
                                           @Value("${schedula.auth.enabled:true}") boolean enabled) {
        return args -> {
            if (!enabled) return;
            keys.seedTenantKey(DEFAULT_TENANT, providedKey);
        };
    }
}
