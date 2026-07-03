package io.github.databaseaudits.audit.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import io.github.databaseaudits.jdbc.CatalogQueries;
import io.github.databaseaudits.platform.DatabasePlatform;

/**
 * Pure unit tests of the audit's missing-primary-key detection and SQL routing
 * with hand-built rows; the injected {@link CatalogQueries} is an inert mock.
 * The end-to-end path is covered by {@link CatalogAuditsIT}.
 */
class PrimaryKeyPresenceAuditTest {

    private static PrimaryKeyPresenceAudit auditFor(
            final DatabasePlatform platform) {
        return new PrimaryKeyPresenceAudit(mock(CatalogQueries.class), platform);
    }

    private static Map<String, @Nullable Object> tableRow(final String table) {
        final Map<String, @Nullable Object> row =
                new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        row.put("table_name", table);
        return row;
    }

    @Test
    void testSql_AllPlatforms_UseSharedInformationSchemaSql() {
        final String postgresqlSql = auditFor(DatabasePlatform.POSTGRESQL).sql();
        assertThat(postgresqlSql)
                .contains("PRIMARY KEY")
                .contains("BASE TABLE");
        assertThat(auditFor(DatabasePlatform.MYSQL).sql())
                .as("MySQL should use the same information_schema SQL as PostgreSQL.")
                .isEqualTo(postgresqlSql);
        assertThat(auditFor(DatabasePlatform.MARIADB).sql())
                .as("MariaDB should use the same information_schema SQL as PostgreSQL.")
                .isEqualTo(postgresqlSql);
        assertThat(auditFor(DatabasePlatform.H2).sql())
                .as("H2 should use the same information_schema SQL as PostgreSQL.")
                .isEqualTo(postgresqlSql);
    }

    @Test
    void testLiquibaseBookkeepingTables_ContainsBothChangelogTables() {
        assertThat(PrimaryKeyPresenceAudit.LIQUIBASE_BOOKKEEPING_TABLES)
                .as("Liquibase bookkeeping tables constant should name both changelog tables.")
                .containsExactlyInAnyOrder("databasechangelog",
                        "databasechangeloglock");
    }

    @Test
    void testAudit_TableWithoutPrimaryKey_ReportsTableName() {
        final CatalogQueries catalogQueries = mock(CatalogQueries.class);
        final var audit = new PrimaryKeyPresenceAudit(catalogQueries,
                DatabasePlatform.POSTGRESQL);
        when(catalogQueries.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(tableRow("orders")));

        assertThat(audit.audit("public", Set.of()))
                .as("Table missing a primary key should be reported by name.")
                .containsExactly("orders");
    }

    @Test
    void testAudit_MultipleMissingPrimaryKeys_ReportsAllTableNames() {
        final CatalogQueries catalogQueries = mock(CatalogQueries.class);
        final var audit = new PrimaryKeyPresenceAudit(catalogQueries,
                DatabasePlatform.POSTGRESQL);
        when(catalogQueries.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(tableRow("orders"), tableRow("items")));

        assertThat(audit.audit("public", Set.of()))
                .as("Each table missing a primary key should be reported.")
                .containsExactlyInAnyOrder("orders", "items");
    }

    @Test
    void testAudit_LiquibaseBookkeepingTablesExcluded_ReturnsNoViolations() {
        final CatalogQueries catalogQueries = mock(CatalogQueries.class);
        final var audit = new PrimaryKeyPresenceAudit(catalogQueries,
                DatabasePlatform.POSTGRESQL);
        when(catalogQueries.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(tableRow("databasechangelog"),
                        tableRow("databasechangeloglock")));

        assertThat(audit.audit("public",
                PrimaryKeyPresenceAudit.LIQUIBASE_BOOKKEEPING_TABLES))
                .as("Liquibase bookkeeping tables excluded via the provided constant should produce no violations.")
                .isEmpty();
    }

    @Test
    void testAudit_PartialExclusion_ReportsOnlyNonExcludedTables() {
        final CatalogQueries catalogQueries = mock(CatalogQueries.class);
        final var audit = new PrimaryKeyPresenceAudit(catalogQueries,
                DatabasePlatform.POSTGRESQL);
        when(catalogQueries.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(tableRow("orders"),
                        tableRow("databasechangelog")));

        assertThat(audit.audit("public", Set.of("databasechangelog")))
                .as("Only the non-excluded table should be reported.")
                .containsExactly("orders");
    }

    @Test
    void testAudit_AllTablesHavePrimaryKeys_ReturnsNoViolations() {
        final CatalogQueries catalogQueries = mock(CatalogQueries.class);
        final var audit = new PrimaryKeyPresenceAudit(catalogQueries,
                DatabasePlatform.POSTGRESQL);
        when(catalogQueries.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of());

        assertThat(audit.audit("public", Set.of()))
                .as("No tables missing primary keys should produce no violations.")
                .isEmpty();
    }
}
