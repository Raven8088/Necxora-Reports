package com.nexora.reports.plan2.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A4-B2: semantics of the JSON Schema subset used by the contracts, and fail-closed loading. */
class ContractSchemaTest {

    private static Map<String, Object> schema(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @Test
    void refusesToLoadASchemaWithAnUnsupportedKeyword() {
        assertThatThrownBy(() -> ContractSchema.compile(schema("type", "array", "uniqueItems", true)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContractSchema.compile(schema("properties", schema("a",
                schema("contentEncoding", "base64"))))).isInstanceOf(IllegalArgumentException.class);
    }

    /** A4-B9 (D-B9-3): {@code format} is an annotation in JSON Schema 2020-12; the pattern beside it is what checks. */
    @Test
    void formatIsAnAnnotationAndNeverAnAssertion() {
        ContractSchema s = ContractSchema.compile(schema("properties", schema("at", schema("type", "string",
                "format", "date-time", "pattern", "^\\d{4}-\\d{2}-\\d{2}T"))));

        assertThat(s.firstError(Map.of("at", "2026-11-01T10:42:03Z"))).isEmpty();
        assertThat(s.firstError(Map.of("at", "not a date"))).contains("$.at");
        assertThat(ContractSchema.compile(schema("type", "string", "format", "unknown-format"))
                .firstError("anything")).isEmpty();
    }

    @Test
    void refusesExternalOrUnresolvedReferences() {
        assertThatThrownBy(() -> ContractSchema.compile(schema("$ref", "https://example.org/x.json")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContractSchema.compile(schema("$ref", "#/$defs/missing")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eachKeywordAppliesOnlyToInstancesOfItsType() {
        ContractSchema s = ContractSchema.compile(schema("minItems", 2L, "minLength", 3L, "minimum", 5L));

        assertThat(s.firstError("abc")).isEmpty();
        assertThat(s.firstError("ab")).isPresent();
        assertThat(s.firstError(new ArrayList<>(List.of(1L)))).isPresent();
        assertThat(s.firstError(7L)).isEmpty();
        assertThat(s.firstError(4L)).isPresent();
        assertThat(s.firstError(true)).isEmpty();
    }

    @Test
    void oneOfRequiresExactlyOneMatchingAlternative() {
        ContractSchema s = ContractSchema.compile(schema("oneOf", List.of(schema("type", "string"),
                schema("minLength", 1L))));

        assertThat(s.firstError(5L)).isEmpty();
        assertThat(s.firstError("abc")).isPresent();
    }

    @Test
    void ifThenElseAppliesTheBranchSelectedByTheCondition() {
        ContractSchema s = ContractSchema.compile(schema(
                "if", schema("properties", schema("status", schema("const", "READY")), "required", List.of("status")),
                "then", schema("properties", schema("items", schema("minItems", 1L))),
                "else", schema("properties", schema("items", schema("maxItems", 0L)))));

        assertThat(s.firstError(schema("status", "READY", "items", new ArrayList<>()))).isPresent();
        assertThat(s.firstError(schema("status", "READY", "items", new ArrayList<>(List.of(1L))))).isEmpty();
        assertThat(s.firstError(schema("status", "DRAFT", "items", new ArrayList<>(List.of(1L))))).isPresent();
    }

    @Test
    void reportsTheLocationOfUnknownOrMissingPropertiesButNeverValues() {
        ContractSchema s = ContractSchema.compile(schema("type", "object", "additionalProperties", false,
                "required", List.of("id"),
                "properties", schema("id", schema("type", "string", "pattern", "^[a-z]+$"),
                        "list", schema("type", "array", "items", schema("$ref", "#/$defs/n"))),
                "$defs", schema("n", schema("type", "integer", "maximum", 9L))));

        assertThat(s.firstError(schema("id", "abc", "sql", "select secret"))).contains("$.sql");
        assertThat(s.firstError(schema("list", new ArrayList<>()))).contains("$.id");
        assertThat(s.firstError(schema("id", "ABC"))).contains("$.id");
        assertThat(s.firstError(schema("id", "abc", "list", new ArrayList<>(List.of(1L, 10L))))).contains("$.list[1]");
        assertThat(s.firstError(schema("id", "abc", "list", new ArrayList<>(List.of(1L, 9L))))).isEmpty();
    }
}
