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
 * Pure unit tests of the audit's nullable-column detection and SQL routing with
 * hand-built rows; the injected {@link CatalogQueries} is an inert mock. The
 * end-to-end path is covered by {@link CatalogAuditsIT}.
 */
class ForeignKeyNotNullAuditTest {

    private static ForeignKeyNotNullAudit auditFor(
            final DatabasePlatform platform) {
        return new ForeignKeyNotNullAudit(mock(CatalogQueries.class), platform);
    }

    private static Map<String, @Nullable Object> nullableColumnRow(
            final String table, final String constraint, final String column) {
        final Map<String, @Nullable Object> row =
                new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        row.put("table_name", table);
        row.put("constraint_name", constraint);
        row.put("column_name", column);
        return row;
    }

    @Test
    void testSql_AllPlatforms_UseSharedInformationSchemaSql() {
        final String postgresqlSql = auditFor(DatabasePlatform.POSTGRESQL).sql();
        assertThat(postgresqlSql)
                .contains("FOREIGN KEY")
                .contains("is_nullable");
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
    void testAudit_NullableFkColumn_ReportsTableColumnAndConstraintName() {
        final CatalogQueries jdbcSupport = mock(CatalogQueries.class);
        final var audit = new ForeignKeyNotNullAudit(jdbcSupport,
                DatabasePlatform.POSTGRESQL);
        when(jdbcSupport.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(
                        nullableColumnRow("child", "fk_child_parent", "parent_id")));

        assertThat(audit.audit("public", Set.of()))
                .as("Nullable FK column should be reported with table, column, and constraint.")
                .anySatisfy(violation -> assertThat(violation)
                        .contains("child.parent_id")
                        .contains("fk_child_parent")
                        .contains("is nullable"));
    }

    @Test
    void testAudit_MultipleNullableColumns_ReportsEachOne() {
        final CatalogQueries jdbcSupport = mock(CatalogQueries.class);
        final var audit = new ForeignKeyNotNullAudit(jdbcSupport,
                DatabasePlatform.POSTGRESQL);
        when(jdbcSupport.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(
                        nullableColumnRow("child", "fk_child_parent", "parent_id"),
                        nullableColumnRow("child", "fk_child_other", "other_id")));

        assertThat(audit.audit("public", Set.of()))
                .as("Each nullable FK column should be reported separately.")
                .hasSize(2);
    }

    @Test
    void testAudit_ExcludedColumn_ReturnsNoViolations() {
        final CatalogQueries jdbcSupport = mock(CatalogQueries.class);
        final var audit = new ForeignKeyNotNullAudit(jdbcSupport,
                DatabasePlatform.POSTGRESQL);
        when(jdbcSupport.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(
                        nullableColumnRow("child", "fk_child_parent", "parent_id")));

        assertThat(audit.audit("public", Set.of("child.parent_id")))
                .as("Excluded column should not produce a violation.")
                .isEmpty();
    }

    @Test
    void testAudit_PartialExclusion_ReportsOnlyNonExcludedColumns() {
        final CatalogQueries jdbcSupport = mock(CatalogQueries.class);
        final var audit = new ForeignKeyNotNullAudit(jdbcSupport,
                DatabasePlatform.POSTGRESQL);
        when(jdbcSupport.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(
                        nullableColumnRow("child", "fk_child_parent", "parent_id"),
                        nullableColumnRow("child", "fk_child_other", "other_id")));

        final List<String> violations =
                audit.audit("public", Set.of("child.parent_id"));
        assertThat(violations)
                .as("Only the non-excluded column should be reported.")
                .hasSize(1)
                .anySatisfy(v -> assertThat(v).contains("other_id"));
    }

    @Test
    void testAudit_NoNullableColumns_ReturnsNoViolations() {
        final CatalogQueries jdbcSupport = mock(CatalogQueries.class);
        final var audit = new ForeignKeyNotNullAudit(jdbcSupport,
                DatabasePlatform.POSTGRESQL);
        when(jdbcSupport.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of());

        assertThat(audit.audit("public", Set.of()))
                .as("No nullable FK columns should produce no violations.")
                .isEmpty();
    }
}
