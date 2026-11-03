package com.nexora.reports.plan2.domain;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validator for the JSON Schema 2020-12 subset used by the Nexora contracts that Reports reads
 * ({@code nexora-report-spec/1}, {@code nexora-logical-plan/2}, {@code nexora-report-plan/1}).
 * <p>
 * Supported keywords: {@code type}, {@code properties}, {@code required}, {@code additionalProperties},
 * {@code const}, {@code enum}, {@code pattern}, {@code minLength}, {@code maxLength}, {@code minItems},
 * {@code maxItems}, {@code items}, {@code minimum}, {@code maximum}, {@code allOf}, {@code oneOf},
 * {@code if}/{@code then}/{@code else} and local {@code $ref} ({@code #/$defs/...}). Annotations ({@code $schema},
 * {@code $id}, {@code title}, {@code description}, {@code $comment} and, since A4-B9, {@code format}) are ignored:
 * in JSON Schema 2020-12 {@code format} is an annotation unless a format-assertion vocabulary is declared, which the
 * Nexora contracts do not do; they state the constraint with a {@code pattern} as well
 * ({@code nexora-report-run/1}).
 * <p>
 * <b>Fails closed:</b> {@link #compile} rejects any other keyword, so a future schema that relies on something this
 * class does not implement is detected when Reports loads it, instead of being silently ignored. As in JSON Schema,
 * each keyword applies only to instances of its type (for example, {@code minItems} ignores strings).
 * <p>
 * The instance is a tree of maps, lists, strings, booleans, integers ({@code Integer}, {@code Long},
 * {@code BigInteger}) and null. The result is the location of the first violation, never its value.
 */
public final class ContractSchema {

    private static final Set<String> ANNOTATIONS = Set.of("$schema", "$id", "title", "description", "$comment",
            "format");
    private static final Set<String> KEYWORDS = Set.of("type", "properties", "required", "additionalProperties",
            "const", "enum", "pattern", "minLength", "maxLength", "minItems", "maxItems", "items", "minimum",
            "maximum", "allOf", "oneOf", "if", "then", "else", "$ref", "$defs");
    private static final Set<String> TYPES = Set.of("object", "array", "string", "boolean", "null", "integer",
            "number");
    private static final String REF_PREFIX = "#/$defs/";

    private final Map<String, Object> root;
    private final Map<String, Pattern> patterns = new HashMap<>();

    private ContractSchema(Map<String, Object> root) {
        this.root = root;
    }

    /** Checks that the schema only uses supported keywords and precompiles its patterns. */
    public static ContractSchema compile(Map<String, Object> schema) {
        ContractSchema compiled = new ContractSchema(Objects.requireNonNull(schema, "schema"));
        compiled.check(schema, "#");
        return compiled;
    }

    /** Location ({@code $.a.b[2]}) of the first violation, or empty if the instance is valid. */
    public Optional<String> firstError(Object instance) {
        return Optional.ofNullable(validate(instance, root, "$"));
    }

    // ---------------------------------------------------------------- compile

    private void check(Object node, String at) {
        if (node instanceof Boolean) {
            return;
        }
        if (!(node instanceof Map<?, ?> raw)) {
            throw unsupported(at, "a schema must be an object or a boolean");
        }
        Map<String, Object> schema = Trees.map(raw);
        for (Map.Entry<String, Object> entry : schema.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            String here = at + "/" + key;
            if (ANNOTATIONS.contains(key)) {
                continue;
            }
            if (!KEYWORDS.contains(key)) {
                throw unsupported(here, "unsupported keyword");
            }
            switch (key) {
                case "$defs", "properties" -> {
                    if (!(value instanceof Map<?, ?> children)) {
                        throw unsupported(here, "must be an object");
                    }
                    for (Map.Entry<?, ?> child : children.entrySet()) {
                        check(child.getValue(), here + "/" + child.getKey());
                    }
                }
                case "additionalProperties", "items", "if", "then", "else" -> check(value, here);
                case "allOf", "oneOf" -> {
                    if (!(value instanceof List<?> list) || list.isEmpty()) {
                        throw unsupported(here, "must be a non-empty array");
                    }
                    for (int i = 0; i < list.size(); i++) {
                        check(list.get(i), here + "/" + i);
                    }
                }
                case "$ref" -> resolve(value, here);
                case "type" -> {
                    List<?> names = value instanceof List<?> list ? list : List.of(value);
                    for (Object name : names) {
                        if (!TYPES.contains(name)) {
                            throw unsupported(here, "unknown type");
                        }
                    }
                }
                case "pattern" -> {
                    if (!(value instanceof String regex)) {
                        throw unsupported(here, "must be a string");
                    }
                    patterns.computeIfAbsent(regex, Pattern::compile);
                }
                case "enum", "required" -> {
                    if (!(value instanceof List<?>)) {
                        throw unsupported(here, "must be an array");
                    }
                }
                case "minLength", "maxLength", "minItems", "maxItems", "minimum", "maximum" -> {
                    if (!(value instanceof Long || value instanceof Integer || value instanceof BigInteger)) {
                        throw unsupported(here, "must be an integer");
                    }
                }
                default -> {
                    // const: any value
                }
            }
        }
    }

    private Object resolve(Object ref, String at) {
        if (!(ref instanceof String text) || !text.startsWith(REF_PREFIX)) {
            throw unsupported(at, "only local references to #/$defs are supported");
        }
        Object defs = root.get("$defs");
        Object target = defs instanceof Map<?, ?> map ? map.get(text.substring(REF_PREFIX.length())) : null;
        if (target == null) {
            throw unsupported(at, "unresolved reference");
        }
        return target;
    }

    private static IllegalArgumentException unsupported(String at, String reason) {
        return new IllegalArgumentException("contract schema not supported at " + at + ": " + reason);
    }

    // ---------------------------------------------------------------- validate

    private String validate(Object instance, Object schemaNode, String path) {
        if (schemaNode instanceof Boolean allowed) {
            return allowed ? null : path;
        }
        Map<String, Object> schema = Trees.map(schemaNode);
        if (schema.containsKey("$ref")) {
            String error = validate(instance, resolve(schema.get("$ref"), path), path);
            if (error != null) {
                return error;
            }
        }
        if (schema.containsKey("type") && !typeMatches(instance, schema.get("type"))) {
            return path;
        }
        if (schema.containsKey("const") && !Objects.equals(normalize(schema.get("const")), normalize(instance))) {
            return path;
        }
        if (schema.containsKey("enum")
                && Trees.list(schema.get("enum")).stream().noneMatch(v -> Objects.equals(normalize(v),
                normalize(instance)))) {
            return path;
        }
        String error = switch (instance) {
            case String text -> string(text, schema, path);
            case List<?> list -> array(list, schema, path);
            case Map<?, ?> map -> object(Trees.map(map), schema, path);
            case Integer i -> number(BigInteger.valueOf(i), schema, path);
            case Long l -> number(BigInteger.valueOf(l), schema, path);
            case BigInteger b -> number(b, schema, path);
            case null, default -> null;
        };
        if (error != null) {
            return error;
        }
        if (schema.containsKey("allOf")) {
            for (Object sub : Trees.list(schema.get("allOf"))) {
                error = validate(instance, sub, path);
                if (error != null) {
                    return error;
                }
            }
        }
        if (schema.containsKey("oneOf")) {
            int matches = 0;
            for (Object sub : Trees.list(schema.get("oneOf"))) {
                if (validate(instance, sub, path) == null) {
                    matches++;
                }
            }
            if (matches != 1) {
                return path;
            }
        }
        if (schema.containsKey("if")) {
            String branch = validate(instance, schema.get("if"), path) == null ? "then" : "else";
            if (schema.containsKey(branch)) {
                return validate(instance, schema.get(branch), path);
            }
        }
        return null;
    }

    private String string(String text, Map<String, Object> schema, String path) {
        if (schema.containsKey("pattern") && !patterns.get((String) schema.get("pattern")).matcher(text).find()) {
            return path;
        }
        int length = text.codePointCount(0, text.length());
        if (schema.containsKey("minLength") && length < Trees.number(schema, "minLength")) {
            return path;
        }
        if (schema.containsKey("maxLength") && length > Trees.number(schema, "maxLength")) {
            return path;
        }
        return null;
    }

    private String array(List<?> list, Map<String, Object> schema, String path) {
        if (schema.containsKey("minItems") && list.size() < Trees.number(schema, "minItems")) {
            return path;
        }
        if (schema.containsKey("maxItems") && list.size() > Trees.number(schema, "maxItems")) {
            return path;
        }
        if (schema.containsKey("items")) {
            for (int i = 0; i < list.size(); i++) {
                String error = validate(list.get(i), schema.get("items"), path + "[" + i + "]");
                if (error != null) {
                    return error;
                }
            }
        }
        return null;
    }

    private String object(Map<String, Object> map, Map<String, Object> schema, String path) {
        if (schema.containsKey("required")) {
            for (Object name : Trees.list(schema.get("required"))) {
                if (!map.containsKey((String) name)) {
                    return path + "." + name;
                }
            }
        }
        Map<String, Object> properties = schema.containsKey("properties") ? Trees.map(schema.get("properties"))
                : Map.of();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String here = path + "." + entry.getKey();
            Object propertySchema = properties.get(entry.getKey());
            String error = null;
            if (propertySchema != null) {
                error = validate(entry.getValue(), propertySchema, here);
            } else if (schema.containsKey("additionalProperties")) {
                error = validate(entry.getValue(), schema.get("additionalProperties"), here);
            }
            if (error != null) {
                return error;
            }
        }
        return null;
    }

    private static String number(BigInteger value, Map<String, Object> schema, String path) {
        if (schema.containsKey("minimum") && value.compareTo(integer(schema.get("minimum"))) < 0) {
            return path;
        }
        if (schema.containsKey("maximum") && value.compareTo(integer(schema.get("maximum"))) > 0) {
            return path;
        }
        return null;
    }

    private static boolean typeMatches(Object instance, Object type) {
        List<?> names = type instanceof List<?> list ? list : List.of(type);
        for (Object name : names) {
            boolean matches = switch ((String) name) {
                case "object" -> instance instanceof Map<?, ?>;
                case "array" -> instance instanceof List<?>;
                case "string" -> instance instanceof String;
                case "boolean" -> instance instanceof Boolean;
                case "null" -> instance == null;
                case "integer", "number" -> instance instanceof Integer || instance instanceof Long
                        || instance instanceof BigInteger;
                default -> false;
            };
            if (matches) {
                return true;
            }
        }
        return false;
    }

    private static BigInteger integer(Object value) {
        return value instanceof BigInteger b ? b : BigInteger.valueOf(((Number) value).longValue());
    }

    /** Integers compare by value whatever their Java type, so {@code const}/{@code enum} match like in JSON. */
    private static Object normalize(Object value) {
        return switch (value) {
            case Integer i -> BigInteger.valueOf(i);
            case Long l -> BigInteger.valueOf(l);
            case List<?> list -> list.stream().map(ContractSchema::normalize).toList();
            case Map<?, ?> map -> {
                Map<Object, Object> copy = new HashMap<>();
                map.forEach((k, v) -> copy.put(k, normalize(v)));
                yield copy;
            }
            case null, default -> value;
        };
    }
}
