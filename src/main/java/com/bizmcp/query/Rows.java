package com.bizmcp.query;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** Null-safe coercion from JDBC row maps to result-record field types. */
public final class Rows {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private Rows() {
    }

    public static String string(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value == null ? null : String.valueOf(value);
    }

    public static long longValue(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    public static Long longOrNull(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value instanceof Number number ? number.longValue() : null;
    }

    public static int intValue(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value instanceof Number number ? number.intValue() : 0;
    }

    public static boolean boolValue(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value instanceof Boolean bool && bool;
    }

    public static BigDecimal decimal(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return switch (value) {
            case null -> BigDecimal.ZERO;
            case BigDecimal decimal -> decimal;
            case Number number -> BigDecimal.valueOf(number.doubleValue());
            default -> BigDecimal.ZERO;
        };
    }

    public static String timestamp(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return switch (value) {
            case null -> null;
            case OffsetDateTime odt -> odt.format(TIMESTAMP);
            case java.sql.Timestamp ts -> ts.toInstant().toString();
            case java.time.Instant instant -> instant.toString();
            default -> String.valueOf(value);
        };
    }
}
