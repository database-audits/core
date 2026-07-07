package io.github.databaseaudits.audit.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import io.github.databaseaudits.audit.finding.Finding;
import io.github.databaseaudits.catalog.IndexCatalog;
import io.github.databaseaudits.catalog.IndexDefinition;
import io.github.databaseaudits.jdbc.CatalogQueries;
import io.github.databaseaudits.platform.DatabasePlatform;

/**
 * Pure unit tests of the audit's nullable-key detection and SQL routing with
 * hand-built rows and {@link IndexDefinition} values; the injected
 * {@link CatalogQueries} and {@link IndexCatalog} are inert mocks. The
 * end-to-end path is covered by {@link CatalogAuditsIT}.
 */
class UniqueIndexNotNullAuditTest {

    private static IndexDefinition index(final String table, final String name,
            final boolean unique, final boolean primary, final boolean partial,
            final String... columns) {
        return new IndexDefinition(table, name, unique, primary, partial,
                Arrays.asList(columns));
    }

    private static Map<String, @Nullable Object> nullableColumnRow(
            final String table, final String column) {
        final Map<String, @Nullable Object> row =
                new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        row.put("table_name", table);
        row.put("column_name", column);
        return row;
    }

    private static UniqueIndexNotNullAudit auditWith(
            final CatalogQueries catalogQueries,
            final List<IndexDefinition> indexes) {
        final IndexCatalog indexCatalog = mock(IndexCatalog.class);
        when(indexCatalog.readAll("public")).thenReturn(indexes);
        return new UniqueIndexNotNullAudit(catalogQueries, indexCatalog,
                DatabasePlatform.POSTGRESQL);
    }

    @Test
    void testSql_EachPlatform_UsesSharedInformationSchemaSql() {
        final CatalogQueries catalogQueries = mock(CatalogQueries.class);
        final IndexCatalog indexCatalog = mock(IndexCatalog.class);
        final String postgresqlSql = new UniqueIndexNotNullAudit(catalogQueries,
                indexCatalog, DatabasePlatform.POSTGRESQL).sql();
        assertThat(postgresqlSql)
                .as("The shared SQL should query by column nullability.")
                .contains("is_nullable");
        assertThat(new UniqueIndexNotNullAudit(catalogQueries, indexCatalog,
                DatabasePlatform.MYSQL).sql())
                .as("MySQL should use the same information_schema SQL as PostgreSQL.")
                .isEqualTo(postgresqlSql);
        assertThat(new UniqueIndexNotNullAudit(catalogQueries, indexCatalog,
                DatabasePlatform.H2).sql())
                .as("H2 should use the same information_schema SQL as PostgreSQL.")
                .isEqualTo(postgresqlSql);
    }

    @Test
    void testAudit_UniqueIndexOnNullableColumn_ReportsColumn() {
        final CatalogQueries catalogQueries = mock(CatalogQueries.class);
        when(catalogQueries.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(nullableColumnRow("t", "code")));
        final var audit = auditWith(catalogQueries,
                List.of(index("t", "uq_t_code", true, false, false, "code")));

        assertThat(audit.audit("public", Set.of()))
                .extracting(Finding::description)
                .as("A unique index over a nullable column should be reported.")
                .containsExactly(
                        "t.uq_t_code is UNIQUE over nullable column(s) [code] — rows with NULLs bypass uniqueness");
    }

    @Test
    void testAudit_UniqueIndexOnNotNullColumn_ReturnsNoViolations() {
        final CatalogQueries catalogQueries = mock(CatalogQueries.class);
        when(catalogQueries.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of());
        final var audit = auditWith(catalogQueries,
                List.of(index("t", "uq_t_code", true, false, false, "code")));

        assertThat(audit.audit("public", Set.of()))
                .as("A unique index over a not-null column should not be flagged.")
                .isEmpty();
    }

    @Test
    void testAudit_PrimaryKeyIndexOnNullableColumn_Skipped() {
        final CatalogQueries catalogQueries = mock(CatalogQueries.class);
        when(catalogQueries.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(nullableColumnRow("t", "id")));
        final var audit = auditWith(catalogQueries,
                List.of(index("t", "t_pkey", true, true, false, "id")));

        assertThat(audit.audit("public", Set.of()))
                .as("A primary key index should never be flagged.")
                .isEmpty();
    }

    @Test
    void testAudit_PartialIndexOnNullableColumn_Skipped() {
        final CatalogQueries catalogQueries = mock(CatalogQueries.class);
        when(catalogQueries.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(nullableColumnRow("t", "code")));
        final var audit = auditWith(catalogQueries, List.of(
                index("t", "uq_t_code_partial", true, false, true, "code")));

        assertThat(audit.audit("public", Set.of()))
                .as("A partial unique index should not be flagged - partial uniqueness is often deliberate.")
                .isEmpty();
    }

    @Test
    void testAudit_ExpressionIndexOnNullableColumn_Skipped() {
        final CatalogQueries catalogQueries = mock(CatalogQueries.class);
        when(catalogQueries.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(nullableColumnRow("t", "code")));
        final IndexDefinition expressionIndex = new IndexDefinition("t",
                "uq_t_expr", true, false, false, Arrays.asList((String) null));
        final var audit = auditWith(catalogQueries, List.of(expressionIndex));

        assertThat(audit.audit("public", Set.of()))
                .as("An expression index cannot be matched to a column, so it should not be flagged.")
                .isEmpty();
    }

    @Test
    void testAudit_NonUniqueIndexOnNullableColumn_Skipped() {
        final CatalogQueries catalogQueries = mock(CatalogQueries.class);
        when(catalogQueries.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(nullableColumnRow("t", "code")));
        final var audit = auditWith(catalogQueries,
                List.of(index("t", "idx_t_code", false, false, false, "code")));

        assertThat(audit.audit("public", Set.of()))
                .as("A non-unique index should never be flagged.")
                .isEmpty();
    }

    @Test
    void testAudit_CompositeUniqueWithOneNullableColumn_ReportsOnlyThatColumn() {
        final CatalogQueries catalogQueries = mock(CatalogQueries.class);
        when(catalogQueries.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(nullableColumnRow("t", "b")));
        final var audit = auditWith(catalogQueries, List.of(
                index("t", "uq_t_ab", true, false, false, "a", "b")));

        assertThat(audit.audit("public", Set.of()))
                .extracting(Finding::description)
                .as("Only the nullable column of the composite key should be named.")
                .containsExactly(
                        "t.uq_t_ab is UNIQUE over nullable column(s) [b] — rows with NULLs bypass uniqueness");
    }

    @Test
    void testAudit_ExcludedIndexName_ReturnsNoViolations() {
        final CatalogQueries catalogQueries = mock(CatalogQueries.class);
        when(catalogQueries.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(nullableColumnRow("t", "code")));
        final var audit = auditWith(catalogQueries,
                List.of(index("t", "uq_t_code", true, false, false, "code")));

        assertThat(audit.audit("public", Set.of("uq_t_code")))
                .as("Excluding the index by name should suppress the finding.")
                .isEmpty();
    }
}
