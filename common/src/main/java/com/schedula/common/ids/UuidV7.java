package com.schedula.common.ids;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID generate() {
        return generate(Instant.now());
    }

    public static UUID generate(Instant time) {
        long ts = time.toEpochMilli();
        long msb = (ts & 0xFFFFFFFFFFFFL) << 16;
        msb |= 0x7 << 12;
        msb |= RANDOM.nextLong() & 0x0FFF;
        long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }
}
