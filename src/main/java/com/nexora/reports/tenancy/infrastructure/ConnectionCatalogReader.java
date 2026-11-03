package com.nexora.reports.tenancy.infrastructure;

import com.nexora.reports.execution.application.port.CatalogReader;
import com.nexora.reports.translation.domain.SqlParameter;
import com.nexora.reports.translation2.domain.SqlTextGuard;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Catalog queries on a connection opened by the registration capture. Same rules as the executor's reader: the
 * text goes through {@link SqlTextGuard} and only text and integer parameters are bound.
 */
final class ConnectionCatalogReader implements CatalogReader {

    private final Connection connection;

    ConnectionCatalogReader(Connection connection) {
        this.connection = connection;
    }

    @Override
    public List<List<Object>> query(String sql, List<SqlParameter> parameters) {
        SqlTextGuard.check(sql);
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.size(); i++) {
                SqlParameter p = parameters.get(i);
                switch (p.type()) {
                    case TEXT -> ps.setString(i + 1, (String) p.value());
                    case BIGINT -> ps.setLong(i + 1, (Long) p.value());
                    default -> throw new IllegalArgumentException("unsupported catalog parameter type");
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                int columns = rs.getMetaData().getColumnCount();
                List<List<Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    List<Object> row = new ArrayList<>(columns);
                    for (int i = 1; i <= columns; i++) {
                        row.add(rs.getObject(i));
                    }
                    rows.add(row);
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("catalog query failed (SQLSTATE " + e.getSQLState() + ")", e);
        }
    }
}
