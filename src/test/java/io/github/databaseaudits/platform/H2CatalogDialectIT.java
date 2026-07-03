package io.github.databaseaudits.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import io.github.databaseaudits.audit.catalog.ForeignKeyTypeMatchAudit;
import io.github.databaseaudits.jdbc.CatalogQueries;

/**
 * Verifies {@link H2CatalogDialect#foreignKeyColumnTypesSql()} renders the
 * precision and scale of decimal-family columns, so
 * {@link ForeignKeyTypeMatchAudit} catches a {@code DECIMAL(10,2)} foreign key
 * that references a {@code DECIMAL(5,0)} key on H2 — the mismatch PostgreSQL's
 * {@code format_type} and MySQL's {@code column_type} already catch. A bare
 * {@code data_type} would collapse both to {@code NUMERIC} and miss it. Runs
 * against embedded H2, so unlike the container-backed {@code CatalogAuditsIT} it
 * needs no Docker.
 */
class H2CatalogDialectIT {
    @Test
    void testForeignKeyTypeMatchAudit_DecimalPrecisionMismatchOnH2_ReportsBothTypesWithPrecisionAndScale()
            throws SQLException {
        final JdbcDataSource dataSource = h2DataSource("fk_decimal_mismatch");
        createForeignKey(dataSource, "DECIMAL(5,0)", "DECIMAL(10,2)");
        final ForeignKeyTypeMatchAudit audit = new ForeignKeyTypeMatchAudit(
                new CatalogQueries(dataSource), DatabasePlatform.H2);

        assertThat(audit.audit("PUBLIC", Set.of()))
                .as("A DECIMAL(10,2) FK referencing a DECIMAL(5,0) key is a mismatch on H2, rendered with precision and scale.")
                .anySatisfy(violation -> assertThat(violation)
                        .contains("CHILD.PARENT_REF")
                        .contains("NUMERIC(10,2)")
                        .contains("NUMERIC(5,0)"));
    }

    @Test
    void testForeignKeyTypeMatchAudit_EqualDecimalPrecisionOnH2_ReportsNoMismatch()
            throws SQLException {
        final JdbcDataSource dataSource = h2DataSource("fk_decimal_match");
        createForeignKey(dataSource, "DECIMAL(10,2)", "DECIMAL(10,2)");
        final ForeignKeyTypeMatchAudit audit = new ForeignKeyTypeMatchAudit(
                new CatalogQueries(dataSource), DatabasePlatform.H2);

        assertThat(audit.audit("PUBLIC", Set.of()))
                .as("Equal DECIMAL(10,2) types on both sides of the FK are not a mismatch.")
                .isEmpty();
    }

    private static JdbcDataSource h2DataSource(final String name) {
        final JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        return dataSource;
    }

    private static void createForeignKey(final JdbcDataSource dataSource,
            final String parentKeyType, final String childColumnType)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE parent (id " + parentKeyType + " PRIMARY KEY)");
            statement.execute("CREATE TABLE child ("
                    + "id BIGINT PRIMARY KEY, "
                    + "parent_ref " + childColumnType + " NOT NULL, "
                    + "CONSTRAINT fk_child_parent FOREIGN KEY (parent_ref) REFERENCES parent(id))");
        }
    }
}
