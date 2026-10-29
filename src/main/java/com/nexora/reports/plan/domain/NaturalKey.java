package com.nexora.reports.plan.domain;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Parser of Semantic natural keys. Each component is a double-quoted identifier with embedded quotes doubled,
 * joined by dots: {@code "schema"."relation"} for tables and views, {@code "schema"."relation"."column"} for columns.
 * <p>
 * Parsing is strict: anything that is not exactly that grammar is rejected rather than "repaired". Components are
 * returned unquoted (the raw PostgreSQL identifiers); SQL quoting is done later, once, by the translator.
 * Identifiers longer than PostgreSQL's 63-byte limit are rejected because PostgreSQL would silently truncate them
 * and could resolve a different object.
 */
public final class NaturalKey {

    /** NAMEDATALEN - 1 in a default PostgreSQL build. */
    public static final int MAX_IDENTIFIER_BYTES = 63;

    private NaturalKey() {
    }

    /** A relation reference (schema and relation name). */
    public record RelationName(String schema, String name) {
        public String naturalKey() {
            return quote(schema) + "." + quote(name);
        }
    }

    /** A column reference. */
    public record ColumnName(String schema, String relation, String column) {
        public RelationName relationName() {
            return new RelationName(schema, relation);
        }
    }

    public static Optional<RelationName> relation(String naturalKey) {
        return components(naturalKey).filter(c -> c.size() == 2).map(c -> new RelationName(c.get(0), c.get(1)));
    }

    public static Optional<ColumnName> column(String naturalKey) {
        return components(naturalKey).filter(c -> c.size() == 3)
                .map(c -> new ColumnName(c.get(0), c.get(1), c.get(2)));
    }

    /** Components of a well-formed key, or empty if the text does not follow the grammar exactly. */
    static Optional<List<String>> components(String naturalKey) {
        if (naturalKey == null || naturalKey.isEmpty()) {
            return Optional.empty();
        }
        List<String> parts = new ArrayList<>();
        int i = 0;
        int n = naturalKey.length();
        while (true) {
            if (i >= n || naturalKey.charAt(i) != '"') {
                return Optional.empty();
            }
            i++;
            StringBuilder identifier = new StringBuilder();
            boolean closed = false;
            while (i < n) {
                char ch = naturalKey.charAt(i);
                if (ch == '"') {
                    if (i + 1 < n && naturalKey.charAt(i + 1) == '"') {
                        identifier.append('"');
                        i += 2;
                        continue;
                    }
                    closed = true;
                    i++;
                    break;
                }
                if (ch == '\0') {
                    return Optional.empty();
                }
                identifier.append(ch);
                i++;
            }
            if (!closed || identifier.isEmpty()
                    || identifier.toString().getBytes(StandardCharsets.UTF_8).length > MAX_IDENTIFIER_BYTES) {
                return Optional.empty();
            }
            parts.add(identifier.toString());
            if (i == n) {
                return Optional.of(List.copyOf(parts));
            }
            if (naturalKey.charAt(i) != '.') {
                return Optional.empty();
            }
            i++;
        }
    }

    /** Quotes one identifier the way Semantic (and PostgreSQL) do. */
    public static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
