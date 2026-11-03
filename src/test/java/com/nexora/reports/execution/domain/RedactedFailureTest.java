package com.nexora.reports.execution.domain;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A4-B10 (R4-23, D-B10-13): an unexpected failure as written to the logs keeps its classes and stack frames and
 * loses its messages. The first test shows the exposure that motivated it: logging frameworks print the message of
 * the throwable and of every cause, so a value carried by an exception reaches the logs.
 */
class RedactedFailureTest {

    private static final String VALUE = "Pozo confidencial 42";

    @Test
    void anUnexpectedFailureLoggedAsItIsExposesTheValuesItCarries() {
        RuntimeException original = failure();

        assertThat(printed(original)).contains(VALUE);
    }

    @Test
    void theRedactedFailureWithholdsEveryMessageAndKeepsTheDiagnostics() {
        RuntimeException original = failure();

        RedactedFailure redacted = RedactedFailure.of(original);
        String printed = printed(redacted);

        assertThat(printed).doesNotContain(VALUE).doesNotContain("Key (name)");
        assertThat(printed).contains(IllegalStateException.class.getName(), SQLException.class.getName());
        assertThat(redacted.getStackTrace()).isEqualTo(original.getStackTrace());
        assertThat(redacted.getCause()).isInstanceOf(RedactedFailure.class);
        assertThat(redacted.getCause().getStackTrace()).isEqualTo(original.getCause().getStackTrace());
        assertThat(redacted.getSuppressed()).isEmpty();
        // The original is left as it was.
        assertThat(original.getMessage()).contains(VALUE);
    }

    @Test
    void aCycleOrAVeryLongChainOfCausesEnds() {
        RuntimeException a = new RuntimeException("a " + VALUE);
        RuntimeException b = new RuntimeException("b " + VALUE, a);
        a.initCause(b);

        String printed = printed(RedactedFailure.of(a));

        assertThat(printed).doesNotContain(VALUE);
    }

    private static RuntimeException failure() {
        SQLException cause = new SQLException("duplicate key value violates unique constraint; Key (name)=(" + VALUE
                + ") already exists", "23505");
        RuntimeException failure = new IllegalStateException("could not process row " + VALUE, cause);
        failure.addSuppressed(new RuntimeException("suppressed " + VALUE));
        return failure;
    }

    private static String printed(Throwable t) {
        StringWriter out = new StringWriter();
        t.printStackTrace(new PrintWriter(out));
        return out.toString();
    }
}
