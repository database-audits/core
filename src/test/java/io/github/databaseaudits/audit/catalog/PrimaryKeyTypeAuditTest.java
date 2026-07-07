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

import io.github.databaseaudits.audit.finding.Finding;
import io.github.databaseaudits.jdbc.CatalogQueries;
import io.github.databaseaudits.platform.DatabasePlatform;

/**
 * Pure unit tests of the audit's narrow-type detection and SQL routing with
 * hand-built rows; the injected {@link CatalogQueries} is an inert mock. The
 * end-to-end path is covered by {@link CatalogAuditsIT}.
 */
class PrimaryKeyTypeAuditTest {

    private static PrimaryKeyTypeAudit auditFor(
            final DatabasePlatform platform) {
        return new PrimaryKeyTypeAudit(mock(CatalogQueries.class), platform);
    }

    private static Map<String, @Nullable Object> pkRow(final String table,
            final String column, final String dataType) {
        final Map<String, @Nullable Object> row =
                new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        row.put("table_name", table);
        row.put("column_name", column);
        row.put("data_type", dataType);
        return row;
    }

    @Test
    void testSql_AllPlatforms_UseSharedInformationSchemaSql() {
        final String postgresqlSql = auditFor(DatabasePlatform.POSTGRESQL).sql();
        assertThat(postgresqlSql)
                .as("The shared SQL should query by PRIMARY KEY constraint type.")
                .contains("PRIMARY KEY");
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
    void testIsNarrow_IntegerFamilyTypes_True() {
        final var audit = auditFor(DatabasePlatform.POSTGRESQL);
        assertThat(audit.isNarrow(pkRow("t", "id", "int")))
                .as("int should be narrower than bigint.").isTrue();
        assertThat(audit.isNarrow(pkRow("t", "id", "INTEGER")))
                .as("INTEGER should be narrower than bigint, matched case-insensitively.")
                .isTrue();
        assertThat(audit.isNarrow(pkRow("t", "id", "smallint")))
                .as("smallint should be narrower than bigint.").isTrue();
        assertThat(audit.isNarrow(pkRow("t", "id", "mediumint")))
                .as("mediumint should be narrower than bigint.").isTrue();
        assertThat(audit.isNarrow(pkRow("t", "id", "tinyint")))
                .as("tinyint should be narrower than bigint.").isTrue();
    }

    @Test
    void testIsNarrow_WideOrNonIntegerTypes_False() {
        final var audit = auditFor(DatabasePlatform.POSTGRESQL);
        assertThat(audit.isNarrow(pkRow("t", "id", "bigint")))
                .as("bigint should not be narrow.").isFalse();
        assertThat(audit.isNarrow(pkRow("t", "id", "BIGINT")))
                .as("BIGINT should not be narrow, matched case-insensitively.")
                .isFalse();
        assertThat(audit.isNarrow(pkRow("t", "id", "uuid")))
                .as("uuid should not be narrow.").isFalse();
        assertThat(audit.isNarrow(pkRow("t", "id", "varchar")))
                .as("varchar should not be narrow.").isFalse();
        assertThat(audit.isNarrow(pkRow("t", "id", "numeric")))
                .as("numeric should not be narrow.").isFalse();
    }

    @Test
    void testAudit_NarrowPrimaryKey_ReportsTableColumnAndType() {
        final CatalogQueries catalogQueries = mock(CatalogQueries.class);
        final var audit =
                new PrimaryKeyTypeAudit(catalogQueries, DatabasePlatform.POSTGRESQL);
        when(catalogQueries.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(pkRow("orders", "id", "integer"),
                        pkRow("customers", "id", "bigint")));

        assertThat(audit.audit("public", Set.of()))
                .extracting(Finding::description)
                .as("Only the narrow primary key should be reported.")
                .containsExactly(
                        "orders.id primary key type integer is narrower than bigint — risks key exhaustion");
    }

    @Test
    void testAudit_CaseInsensitiveExclusion_ReturnsNoViolations() {
        final CatalogQueries catalogQueries = mock(CatalogQueries.class);
        final var audit =
                new PrimaryKeyTypeAudit(catalogQueries, DatabasePlatform.MYSQL);
        when(catalogQueries.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(pkRow("ORDERS", "ID", "int")));

        assertThat(audit.audit("public", Set.of("orders.id")))
                .as("A lower-case exclusion should suppress the upper-case-reported column.")
                .isEmpty();
    }

    @Test
    void testAudit_NoNarrowPrimaryKeys_ReturnsNoViolations() {
        final CatalogQueries catalogQueries = mock(CatalogQueries.class);
        final var audit =
                new PrimaryKeyTypeAudit(catalogQueries, DatabasePlatform.POSTGRESQL);
        when(catalogQueries.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(pkRow("orders", "id", "bigint"),
                        pkRow("customers", "id", "uuid")));

        assertThat(audit.audit("public", Set.of()))
                .as("No narrow primary keys should produce no violations.")
                .isEmpty();
    }

    @Test
    void testAudit_EmptyResult_ReturnsNoViolations() {
        final CatalogQueries catalogQueries = mock(CatalogQueries.class);
        final var audit =
                new PrimaryKeyTypeAudit(catalogQueries, DatabasePlatform.POSTGRESQL);
        when(catalogQueries.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of());

        assertThat(audit.audit("public", Set.of()))
                .as("An empty catalog result should produce no violations.")
                .isEmpty();
    }
}
