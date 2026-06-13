package io.github.databaseaudits.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.sql.DataSource;

import org.jspecify.annotations.Nullable;

import lombok.AllArgsConstructor;

/**
 * Minimal plain-JDBC plumbing for the catalog-driven audits — run a
 * parameterized query and read the rows as column-name→value maps. An injected
 * collaborator wrapping the {@link DataSource}.
 */
@AllArgsConstructor
public class CatalogQueries {
    private final DataSource dataSource;

    /**
     * Runs {@code sql} with the given positional arguments bound and returns
     * every row as a column-label→value map. Lookups are case-insensitive,
     * because drivers disagree on alias case (PostgreSQL lower-cases unquoted
     * aliases, H2 upper-cases them) — the audits read labels in lower case.
     *
     * @param sql
     *                 The SQL query to execute.
     * @param args
     *                 Positional bind arguments, in order.
     * @return Every row as a case-insensitive column-label-to-value map.
     * @throws IllegalStateException
     *                                   Wrapping the {@link SQLException} if
     *                                   the query cannot be run; for an audit
     *                                   that is a test error worth surfacing,
     *                                   never something to swallow.
     */
    public List<Map<String, @Nullable Object>> queryForList(final String sql,
            final Object... args) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                final ResultSetMetaData metaData = resultSet.getMetaData();
                final int columnCount = metaData.getColumnCount();
                final List<Map<String, @Nullable Object>> rows =
                        new ArrayList<>();
                while (resultSet.next()) {
                    final Map<String, @Nullable Object> row =
                            new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                    for (int column = 1; column <= columnCount; column++) {
                        row.put(metaData.getColumnLabel(column),
                                resultSet.getObject(column));
                    }
                    rows.add(row);
                }
                return rows;
            }
        } catch (final SQLException e) {
            throw new IllegalStateException(
                    "Catalog query failed:%n%s".formatted(sql), e);
        }
    }
}
