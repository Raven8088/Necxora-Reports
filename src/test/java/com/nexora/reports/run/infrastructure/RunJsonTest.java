package com.nexora.reports.run.infrastructure;

import com.nexora.reports.result.domain.Methodology;
import com.nexora.reports.result.domain.ResultWarning;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A4-B8: the JSON of the datasets and warnings of an outcome; PostgreSQL parses it back in the integration tests. */
class RunJsonTest {

    @Test
    void datasetsAreWrittenWithEveryField() {
        UUID plan = UUID.fromString("b8000000-0000-4000-8000-000000000002");
        String json = RunJson.datasets(List.of(new Methodology.DatasetExecution("detalle", plan, "f".repeat(64), 35,
                true, 35, 12)));
        assertThat(json).isEqualTo("[{\"datasetId\":\"detalle\",\"planId\":\"" + plan + "\",\"planFingerprint\":\""
                + "f".repeat(64) + "\",\"rowCount\":35,\"truncated\":true,\"maxRows\":35,\"durationMs\":12}]");
        assertThat(RunJson.datasets(List.of())).isEqualTo("[]");
    }

    @Test
    void warningsKeepANullOutput() {
        assertThat(RunJson.warnings(List.of(new ResultWarning("dataset_truncated", "detalle", null, 3, 3))))
                .isEqualTo("[{\"code\":\"dataset_truncated\",\"datasetId\":\"detalle\",\"output\":null,"
                        + "\"delivered\":3,\"limit\":3}]");
    }

    @Test
    void everyCharacterJsonReservesIsEscaped() {
        assertThat(RunJson.string("a\"b\\c\nd\re\tf\u0001g ")).isEqualTo(
                "\"a\\\"b\\\\c\\nd\\re\\tf\\u0001g\\u2028\"");
        assertThat(RunJson.string(null)).isEqualTo("null");
        assertThat(RunJson.string("Metodología «x»")).isEqualTo("\"Metodología «x»\"");
    }
}
