package com.schedula.common.ids;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UuidV7Test {

    @Test
    void versionAndVariantBitsAreCorrect() {
        UUID id = UuidV7.generate();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(UUID.class.cast(id).variant());
    }

    @Test
    void encodesTimestampInHighBits() {
        Instant t = Instant.ofEpochMilli(1_800_000_000_123L);
        UUID id = UuidV7.generate(t);
        long ts = (id.getMostSignificantBits() >>> 16) & 0xFFFFFFFFFFFFL;
        assertThat(ts).isEqualTo(1_800_000_000_123L);
    }

    @Test
    void uniqueAcrossManyGenerations() {
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            assertThat(seen.add(UuidV7.generate())).isTrue();
        }
    }
}
