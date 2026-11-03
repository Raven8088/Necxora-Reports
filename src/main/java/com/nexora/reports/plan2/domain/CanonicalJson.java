package com.nexora.reports.plan2.domain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical JSON of the Nexora contracts ({@code nexora-contracts/README.md}, "Huellas"): object keys sorted by code
 * point, no whitespace, separators {@code ,} and {@code :}, UTF-8 without escaping non-ASCII characters, and the
 * escapes of Python's {@code json.dumps(..., ensure_ascii=False)}. It must produce exactly the bytes Nexora Semantic
 * produces, so that Reports can recompute every fingerprint independently (risk R-1 of the A4 proposal).
 * <p>
 * Documents only hold objects, arrays, strings, integers, booleans and null. Any other value (fractional numbers in
 * particular) is rejected rather than formatted in a way that could differ from Semantic.
 * <p>
 * The SHA-256 digest is a port ({@link Sha256}) implemented in infrastructure with the JDK, so this class stays free
 * of security providers (A4-B2-FIX1).
 */
public final class CanonicalJson {

    /** Name of the field that holds a document's own fingerprint and is excluded from it. */
    public static final String FINGERPRINT_FIELD = "fingerprint";

    private CanonicalJson() {
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        append(out, value);
        return out.toString();
    }

    /** SHA-256 (lowercase hex) of the canonical UTF-8 bytes of the whole document. */
    public static String sha256(Object value, Sha256 digest) {
        return digest.hex(write(value));
    }

    /** Fingerprint of a contract document: SHA-256 of its canonical form without its own {@code fingerprint}. */
    public static String fingerprintOf(Map<String, Object> document, Sha256 digest) {
        Map<String, Object> copy = new LinkedHashMap<>(document);
        copy.remove(FINGERPRINT_FIELD);
        return sha256(copy, digest);
    }

    private static void append(StringBuilder out, Object value) {
        switch (value) {
            case null -> out.append("null");
            case String s -> string(out, s);
            case Boolean b -> out.append(b ? "true" : "false");
            case Integer i -> out.append(i.intValue());
            case Long l -> out.append(l.longValue());
            case BigInteger b -> out.append(b);
            case Map<?, ?> map -> object(out, map);
            case List<?> list -> array(out, list);
            default -> throw new IllegalArgumentException("not a canonical JSON value: " + value.getClass().getName());
        }
    }

    private static void object(StringBuilder out, Map<?, ?> map) {
        List<String> keys = new ArrayList<>();
        for (Object key : map.keySet()) {
            if (!(key instanceof String s)) {
                throw new IllegalArgumentException("object keys must be strings");
            }
            keys.add(s);
        }
        keys.sort(CanonicalJson::compareCodePoints);
        out.append('{');
        boolean first = true;
        for (String key : keys) {
            if (!first) {
                out.append(',');
            }
            first = false;
            string(out, key);
            out.append(':');
            append(out, map.get(key));
        }
        out.append('}');
    }

    private static void array(StringBuilder out, List<?> list) {
        out.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            append(out, list.get(i));
        }
        out.append(']');
    }

    /** Python sorts keys by code point; String.compareTo compares UTF-16 units (differs above U+FFFF). */
    private static int compareCodePoints(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    private static void string(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
