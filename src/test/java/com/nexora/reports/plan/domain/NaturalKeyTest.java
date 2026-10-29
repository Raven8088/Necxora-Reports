package com.nexora.reports.plan.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NaturalKeyTest {

    @Test
    void parsesQuotedComponentsExactly() {
        assertThat(NaturalKey.relation("\"plant\".\"measurements\""))
                .contains(new NaturalKey.RelationName("plant", "measurements"));
        assertThat(NaturalKey.column("\"plant\".\"measurements\".\"value\""))
                .contains(new NaturalKey.ColumnName("plant", "measurements", "value"));
    }

    @Test
    void embeddedQuotesDotsAndSpacesStayInsideTheIdentifier() {
        assertThat(NaturalKey.column("\"we\"\"ird.schema\".\"tab le\".\"va\"\"lue;--\""))
                .contains(new NaturalKey.ColumnName("we\"ird.schema", "tab le", "va\"lue;--"));
        assertThat(NaturalKey.quote("va\"lue")).isEqualTo("\"va\"\"lue\"");
    }

    @Test
    void anythingOutsideTheGrammarIsRejected() {
        assertThat(NaturalKey.relation("plant.measurements")).isEmpty();
        assertThat(NaturalKey.relation("\"plant\".\"measurements\"; DROP TABLE x")).isEmpty();
        assertThat(NaturalKey.relation("\"plant\".\"measurements")).isEmpty();
        assertThat(NaturalKey.relation("\"plant\"..\"measurements\"")).isEmpty();
        assertThat(NaturalKey.relation("\"\".\"measurements\"")).isEmpty();
        assertThat(NaturalKey.relation("\"plant\" .\"measurements\"")).isEmpty();
        assertThat(NaturalKey.relation("\"pl\0ant\".\"measurements\"")).isEmpty();
        assertThat(NaturalKey.relation(null)).isEmpty();
        // Wrong number of components for the kind of object.
        assertThat(NaturalKey.relation("\"plant\".\"measurements\".\"value\"")).isEmpty();
        assertThat(NaturalKey.column("\"plant\".\"measurements\"")).isEmpty();
    }

    @Test
    void identifiersLongerThanPostgresqlAllowsAreRejectedInsteadOfTruncated() {
        String sixtyThree = "a".repeat(63);
        assertThat(NaturalKey.relation("\"s\".\"" + sixtyThree + "\"")).isPresent();
        assertThat(NaturalKey.relation("\"s\".\"" + sixtyThree + "b\"")).isEmpty();
        // Multi-byte characters count in bytes, as in PostgreSQL.
        assertThat(NaturalKey.relation("\"s\".\"" + "é".repeat(32) + "\"")).isEmpty();
    }

    @Test
    void typeCategoriesFollowSemanticAndRefuseAmbiguousTypes() {
        assertThat(TypeCategory.of("int8")).isEqualTo(LogicalPlan.ValueType.NUMERIC);
        assertThat(TypeCategory.of("timestamptz")).isEqualTo(LogicalPlan.ValueType.TIMESTAMP_TZ);
        assertThat(TypeCategory.of("timestamp without time zone")).isEqualTo(LogicalPlan.ValueType.TIMESTAMP);
        assertThat(TypeCategory.of("jsonb")).isEqualTo(LogicalPlan.ValueType.OTHER);
        assertThat(TypeCategory.executable("numeric")).isTrue();
        assertThat(TypeCategory.executable("money")).isFalse();
        assertThat(TypeCategory.executable("timetz")).isFalse();
        assertThat(TypeCategory.executable("jsonb")).isFalse();
    }
}
