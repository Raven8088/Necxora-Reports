package com.nexora.reports.plan2.domain;

import com.nexora.reports.plan2.ContractFixtures;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A4-B2 (risk R-1): Reports must write exactly the canonical bytes Semantic and the contract tools write. */
class CanonicalJsonTest {

    @Test
    void sortsKeysByCodePointWithoutWhitespace() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("é", 1L);
        doc.put("z", new ArrayList<>(Arrays.asList(true, null, "x")));
        doc.put("a", Map.of("y", BigInteger.TWO.pow(70)));
        doc.put("😀", 2L); // U+1F600 sorts after U+FFFF by code point, before it by UTF-16 unit
        doc.put("�", 3L);

        assertThat(CanonicalJson.write(doc)).isEqualTo(
                "{\"a\":{\"y\":1180591620717411303424},\"z\":[true,null,\"x\"],\"é\":1,\"�\":3,\"😀\":2}");
    }

    @Test
    void escapesLikePythonJsonDumpsWithoutAsciiEscaping() {
        assertThat(CanonicalJson.write("q\"b\\n\nr\rt\tb\bf\fc\u0001ñ€"))
                .isEqualTo("\"q\\\"b\\\\n\\nr\\rt\\tb\\bf\\fc\\u0001ñ€\"");
    }

    @Test
    void rejectsValuesWithoutACanonicalForm() {
        assertThatThrownBy(() -> CanonicalJson.write(1.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalJson.write(Map.of("a", 2.0f))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theFingerprintOfADocumentExcludesItsOwnFingerprintField() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("b", "x");
        doc.put("fingerprint", "anything");

        assertThat(CanonicalJson.fingerprintOf(doc, ContractFixtures.SHA256))
                .isEqualTo(CanonicalJson.sha256(Map.of("b", "x"), ContractFixtures.SHA256));
        assertThat(CanonicalJson.sha256(Map.of("b", "x"), ContractFixtures.SHA256))
                .isEqualTo(ContractFixtures.SHA256.hex("{\"b\":\"x\"}"));
        assertThat(doc).containsKey("fingerprint");
    }
}
