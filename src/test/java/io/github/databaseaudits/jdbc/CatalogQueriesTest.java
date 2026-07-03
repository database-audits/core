package io.github.databaseaudits.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests with mocked JDBC — the H2 integration tests cover the
 * real-driver path.
 */
class CatalogQueriesTest {
    private final DataSource dataSource = mock(DataSource.class);
    private final CatalogQueries catalogQueries = new CatalogQueries(dataSource);

    @Test
    void testQueryForList_PositionalArguments_BindsThemAndReadsRowsCaseInsensitively()
            throws SQLException {
        final Connection connection = mock(Connection.class);
        final PreparedStatement statement = mock(PreparedStatement.class);
        final ResultSet resultSet = mock(ResultSet.class);
        final ResultSetMetaData metaData = mock(ResultSetMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("select t.x from t where s = ?"))
                .thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.getMetaData()).thenReturn(metaData);
        when(metaData.getColumnCount()).thenReturn(1);
        // H2-style upper-cased label; the audits read lower-case
        when(metaData.getColumnLabel(1)).thenReturn("TABLE_NAME");
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getObject(1)).thenReturn("orders");

        final List<Map<String, @Nullable Object>> rows = catalogQueries
                .queryForList("select t.x from t where s = ?", "public");

        verify(statement).setObject(1, "public");
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("table_name")).isEqualTo("orders");
    }

    @Test
    void testQueryForList_SqlException_ThrowsIllegalStateNamingSql()
            throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLException("boom"));

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> catalogQueries.queryForList("select 1"))
                .withMessageContaining("select 1");
    }
}
