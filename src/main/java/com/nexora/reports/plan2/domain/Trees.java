package com.nexora.reports.plan2.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Typed access to a JSON tree (maps, lists, strings, booleans, {@code Long}/{@code BigInteger} and null) that has
 * already been validated against its contract schema. A failed cast here is a programming error, never a user error.
 */
final class Trees {

    private Trees() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return (Map<String, Object>) Objects.requireNonNull(value, "object expected");
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object value) {
        return (List<Object>) Objects.requireNonNull(value, "array expected");
    }

    static Map<String, Object> map(Map<String, Object> parent, String key) {
        return map(parent.get(key));
    }

    static List<Object> list(Map<String, Object> parent, String key) {
        return list(parent.get(key));
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> maps(Map<String, Object> parent, String key) {
        return (List<Map<String, Object>>) (List<?>) list(parent, key);
    }

    static String string(Map<String, Object> parent, String key) {
        return (String) parent.get(key);
    }

    static UUID uuid(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        return value == null ? null : UUID.fromString((String) value);
    }

    static long number(Map<String, Object> parent, String key) {
        return ((Number) parent.get(key)).longValue();
    }

    static int integer(Map<String, Object> parent, String key) {
        return Math.toIntExact(number(parent, key));
    }
}
