package com.nexora.reports.run.infrastructure;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
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
 * A4-B8 (D-B8-7, adjustment 4): compensating controls for running without row-level security in A4.
 * <ul>
 *   <li>every statement that reads or deletes rows of the run registry is filtered by {@code tenant_id = ?}, except
 *       the two housekeeping statements, listed explicitly;</li>
 *   <li>statements are parameterized (no quoted values besides fixed constants);</li>
 *   <li>no production class outside {@code run.infrastructure} names a table of the registry.</li>
 * </ul>
 */
class RunSqlTest {

    private static final Pattern TABLES = Pattern.compile(
            "\\breport_(run|run_outcome|artifact|artifact_content|run_event)\\b");

    @Test
    void everyReadOrDeleteIsFilteredByTenantExceptTheListedHousekeeping() throws IllegalAccessException {
        List<String> checked = new ArrayList<>();
        for (Field f : statements()) {
            String sql = (String) f.get(null);
            String upper = sql.toUpperCase(Locale.ROOT);
            boolean readsOrDeletes = upper.contains("SELECT") || upper.contains("DELETE") || upper.contains("UPDATE ");
            if (!TABLES.matcher(sql).find() || !readsOrDeletes) {
                continue;
            }
            if (RunSql.CROSS_TENANT.contains(f.getName())) {
                assertThat(sql.contains("clock_timestamp()")).as(f.getName()).isTrue();
                continue;
            }
            assertThat(sql.contains("tenant_id = ?")).as(f.getName()).isTrue();
            checked.add(f.getName());
        }
        assertThat(checked).containsExactly("FIND_ARTIFACT", "FIND_DATASETS", "FIND_RUN", "FIND_WARNINGS",
                "OUTCOME_OF", "QUOTA_USED", "READ_CONTENT");
        assertThat(RunSql.CROSS_TENANT.size()).isEqualTo(2);
    }

    @Test
    void ownerScopedReadsFilterTenantSourceAndOwner() {
        for (String sql : List.of(RunSql.FIND_RUN, RunSql.FIND_ARTIFACT)) {
            assertThat(sql).contains("tenant_id = ?");
            assertThat(sql).contains("data_source_id = ?");
            assertThat(sql).contains("owner_subject = ?");
        }
    }

    @Test
    void statementsCarryNoValuesBesidesFixedConstants() throws IllegalAccessException {
        Pattern quoted = Pattern.compile("'([^']*)'");
        List<String> allowed = List.of("INTERRUPTED", "[]");
        for (Field f : statements()) {
            var m = quoted.matcher((String) f.get(null));
            while (m.find()) {
                assertThat(allowed.contains(m.group(1))).as(f.getName() + ": " + m.group(1)).isTrue();
            }
            assertThat(((String) f.get(null)).contains("%s")).isFalse();
        }
    }

    @Test
    void onlyTheRunInfrastructureNamesTheRegistryTables() throws IOException {
        Path main = Path.of("src", "main", "java");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(main)) {
            for (Path p : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                String normalized = p.toString().replace('\\', '/');
                if (normalized.contains("/run/infrastructure/")) {
                    continue;
                }
                if (TABLES.matcher(Files.readString(p, StandardCharsets.UTF_8)).find()) {
                    offenders.add(normalized);
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    private static List<Field> statements() {
        List<Field> fields = new ArrayList<>();
        for (Field f : RunSql.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == String.class) {
                f.setAccessible(true);
                fields.add(f);
            }
        }
        fields.sort((a, b) -> a.getName().compareTo(b.getName()));
        return fields;
    }
}
