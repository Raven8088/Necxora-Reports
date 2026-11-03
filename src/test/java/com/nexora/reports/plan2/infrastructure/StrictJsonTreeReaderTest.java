package com.nexora.reports.plan2.infrastructure;

import com.nexora.reports.plan2.domain.PlanV2Exception;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A4-B2: the reader must never let two parties see different values behind the same fingerprint. */
class StrictJsonTreeReaderTest {

    private final StrictJsonTreeReader reader = new StrictJsonTreeReader();

    private Map<String, Object> read(String json) {
        return reader.readObject(json.getBytes(StandardCharsets.UTF_8), "doc");
    }

    @Test
    void readsAPlainTreeWithExactIntegers() {
        Map<String, Object> tree = read("""
                {"s":"ñ","t":true,"n":null,"i":42,"big":123456789012345678901234567890,"a":[1,{"k":"v"}],"o":{}}""");

        assertThat(tree.keySet()).containsExactly("s", "t", "n", "i", "big", "a", "o");
        assertThat(tree.get("s")).isEqualTo("ñ");
        assertThat(tree.get("t")).isEqualTo(true);
        assertThat(tree.get("n")).isNull();
        assertThat(tree.get("i")).isEqualTo(42L);
        assertThat(tree.get("big")).isEqualTo(new BigInteger("123456789012345678901234567890"));
        assertThat(tree.get("a")).isEqualTo(Arrays.asList(1L, Map.of("k", "v")));
        assertThat(tree.get("o")).isEqualTo(Map.of());
    }

    @Test
    void refusesDuplicateKeys() {
        assertThatThrownBy(() -> read("{\"fingerprint\":\"a\",\"fingerprint\":\"b\"}"))
                .isInstanceOf(PlanV2Exception.class);
    }

    @Test
    void refusesTrailingContent() {
        assertThatThrownBy(() -> read("{\"a\":1} {\"a\":2}")).isInstanceOf(PlanV2Exception.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"a\":1.0}", "{\"a\":1.5}", "{\"a\":1e3}", "{\"a\":[2E-1]}"})
    void refusesNumbersThatAreNotIntegers(String json) {
        assertThatThrownBy(() -> read(json)).isInstanceOf(PlanV2Exception.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "\"x\"", "", "{", "{\"a\":}"})
    void refusesAnythingButOneWellFormedObject(String json) {
        assertThatThrownBy(() -> read(json))
                .isInstanceOf(PlanV2Exception.class)
                .satisfies(e -> assertThat(((PlanV2Exception) e).first().path()).isEqualTo("doc"));
    }

    @Test
    void loadsTheThreeContractSchemasBundledWithReports() {
        assertThat(List.of(ClasspathContractSchemas.load(reader).reportSpec(),
                ClasspathContractSchemas.load(reader).logicalPlan(),
                ClasspathContractSchemas.load(reader).reportPlan())).doesNotContainNull();
    }
}
