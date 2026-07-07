package io.github.databaseaudits.audit.jpa;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.hibernate.boot.Metadata;
import org.hibernate.mapping.Column;
import org.hibernate.mapping.Table;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests of the audit's cannot-run guard and its null-schema
 * fallback. The end-to-end schema scan is covered by
 * {@link UnmappedDatabaseObjectAuditIT}.
 */
class UnmappedDatabaseObjectAuditTest {
    @Test
    void testAudit_MetadataNeverCaptured_ThrowsRatherThanReportingVacuously() {
        final var audit = new UnmappedDatabaseObjectAudit(() -> null,
                mock(DataSource.class));

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> audit.audit(Set.of()))
                .as("Auditing without captured metadata must throw rather than report vacuously.")
                .withMessageContaining("metadata was not captured");
    }

    /**
     * MySQL/MariaDB commonly report a {@code null} {@code Connection.getSchema()}
     * (their driver treats the database as the JDBC catalog instead). A
     * {@code null} JDBC schema pattern means "every schema" to
     * {@code DatabaseMetaData}, which would silently violate the "never the
     * whole server" guarantee — so the audit must pass {@code ""}, never
     * {@code null}, to {@code getTables}/{@code getColumns}.
     */
    @Test
    void testAudit_NullConnectionSchema_ScansWithEmptyStringNotNull()
            throws Exception {
        final Table table = mock(Table.class);
        when(table.isPhysicalTable()).thenReturn(true);
        when(table.getSchema()).thenReturn(null);
        when(table.getName()).thenReturn("orders");
        final Column column = mock(Column.class);
        when(column.getName()).thenReturn("id");
        when(table.getColumns()).thenReturn(List.of(column));

        final Metadata metadata = mock(Metadata.class);
        when(metadata.collectTableMappings()).thenReturn(List.of(table));

        final ResultSet emptyResultSet = mock(ResultSet.class);
        when(emptyResultSet.next()).thenReturn(false);
        final DatabaseMetaData databaseMetaData = mock(DatabaseMetaData.class);
        when(databaseMetaData.getTables(any(), any(), any(), any()))
                .thenReturn(emptyResultSet);
        when(databaseMetaData.getColumns(any(), any(), any(), any()))
                .thenReturn(emptyResultSet);

        final Connection connection = mock(Connection.class);
        when(connection.getMetaData()).thenReturn(databaseMetaData);
        when(connection.getCatalog()).thenReturn("mydb");
        when(connection.getSchema()).thenReturn(null);
        final DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        new UnmappedDatabaseObjectAudit(() -> metadata, dataSource)
                .audit(Set.of());

        verify(databaseMetaData).getTables(eq("mydb"), eq(""), isNull(),
                eq(new String[] { "TABLE" }));
        verify(databaseMetaData).getColumns(eq("mydb"), eq(""), isNull(),
                isNull());
    }
}
