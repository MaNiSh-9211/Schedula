package com.schedula.queue;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

final class MappersQ {

    private MappersQ() {
    }

    static UUID uuid(ResultSet rs, String col) throws SQLException {
        Object v = rs.getObject(col);
        if (v == null) return null;
        if (v instanceof UUID u) return u;
        return UUID.fromString(v.toString());
    }

    static Instant instant(ResultSet rs, String col) throws SQLException {
        java.sql.Timestamp ts = rs.getTimestamp(col);
        return ts == null ? null : ts.toInstant();
    }
}
