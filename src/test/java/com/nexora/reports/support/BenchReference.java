package com.nexora.reports.support;

import com.nexora.reports.plan2.ContractFixtures;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * B10-c.1: the independent reference of a report: SQL written by hand from the authentic specification
 * ({@code nexora-testbench/reportes/referencia-b10c/}), executed on the synthetic bench of {@link TlsBench} with the
 * bench's own read-only role ({@code SET ROLE}), apart from Reports' translator and executor. Numbers come back as
 * doubles, as Excel holds them; nulls stay null. The case table ({@code casos.json}) lists the cases and their
 * datasets. Test sources only.
 */
public final class BenchReference {

    public static final String ROOT = "nexora-testbench/reportes/referencia-b10c/";

    private BenchReference() {
    }

    /** {@code casos.json}: the cases, their expected status and, per dataset, the reference SQL and row count. */
    public static Map<String, Object> cases() {
        return ContractFixtures.READER.readObject(ContractFixtures.bytes(ROOT + "casos.json"), "casos.json");
    }

    public static String sql(String file) {
        return new String(ContractFixtures.bytes(ROOT + file), StandardCharsets.UTF_8);
    }

    /** The rows of a reference file, read as the bench's read-only role. */
    public static List<List<Object>> rows(String file) throws SQLException {
        List<List<Object>> rows = new ArrayList<>();
        try (Connection c = TenantDatabase.superuser(TlsBench.DATABASE); Statement s = c.createStatement()) {
            s.execute("SET ROLE " + TlsBench.READER);
            try (ResultSet rs = s.executeQuery(sql(file))) {
                int n = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    List<Object> row = new ArrayList<>();
                    for (int i = 1; i <= n; i++) {
                        Object v = rs.getObject(i);
                        row.add(v instanceof Number number ? (Object) number.doubleValue() : v);
                    }
                    rows.add(row);
                }
            }
        }
        return rows;
    }
}
