package com.nexora.reports.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A4 (R4-24, C3; A4-B9): the production code and configuration of Reports are generic. No client, database or table
 * of a real installation is named in {@code src/main}: what a source holds is known only through Semantic's
 * governed catalog and the report plans it compiles. Tests may use the synthetic bench of A0, never production.
 */
class NoClientSpecificNamesTest {

    private static final Pattern CLIENT_SPECIFIC = Pattern.compile("witsml|wellnode|etp_|laversab");

    @Test
    void noClientSpecificNameAppearsInProductionCodeOrConfiguration() throws IOException {
        Path main = Path.of("src", "main");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(main)) {
            for (Path p : files.filter(Files::isRegularFile).toList()) {
                String text = Files.readString(p, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                if (CLIENT_SPECIFIC.matcher(text).find()) {
                    offenders.add(p.toString().replace('\\', '/'));
                }
            }
        }
        assertThat(offenders).isEmpty();
    }
}
