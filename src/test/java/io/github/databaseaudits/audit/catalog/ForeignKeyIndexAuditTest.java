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

import io.github.databaseaudits.catalog.IndexCatalog;
import io.github.databaseaudits.catalog.IndexDefinition;
import io.github.databaseaudits.jdbc.CatalogQueries;
import io.github.databaseaudits.platform.DatabasePlatform;

/**
 * Pure unit tests of the audit's coverage logic and SQL routing with hand-built
 * {@link IndexDefinition} values; the constructor collaborators are inert
 * mocks. The end-to-end path is covered by {@link CatalogAuditsIT}.
 */
class ForeignKeyIndexAuditTest {

    private final ForeignKeyIndexAudit audit =
            auditFor(DatabasePlatform.POSTGRESQL);

    private static ForeignKeyIndexAudit auditFor(
            final DatabasePlatform platform) {
        return new ForeignKeyIndexAudit(mock(CatalogQueries.class),
                mock(IndexCatalog.class), platform);
    }

    private static IndexDefinition index(final String name,
            final boolean partial, final String... columns) {
        return new IndexDefinition("child", name, false, false, partial,
                Arrays.asList(columns));
    }

    @Test
    void testSql_EachPlatform_RoutesToItsOwnCatalog() {
        assertThat(auditFor(DatabasePlatform.POSTGRESQL).sql())
                .contains("pg_constraint");
        assertThat(auditFor(DatabasePlatform.MYSQL).sql())
                .contains("referenced_table_name");
        assertThat(auditFor(DatabasePlatform.MARIADB).sql())
                .isEqualTo(auditFor(DatabasePlatform.MYSQL).sql());
        assertThat(auditFor(DatabasePlatform.H2).sql())
                .contains("referential_constraints");
    }

    @Test
    void testCovers_IndexLeadingColumnIsFkColumn_CoversSingleColumnFk() {
        assertThat(audit.covers(index("i", false, "customer_id"),
                List.of("customer_id"))).isTrue();
        assertThat(audit.covers(index("i", false, "customer_id", "created_at"),
                List.of("customer_id"))).isTrue();
    }

    @Test
    void testCovers_CompositeFkColumnsInAnyLeadingOrder_Covers() {
        assertThat(audit.covers(index("i", false, "b", "a", "c"),
                List.of("a", "b"))).isTrue();
    }

    @Test
    void testCovers_FkColumnOutsideLeadingBlock_DoesNotCover() {
        assertThat(audit.covers(index("i", false, "a", "c", "b"),
                List.of("a", "b"))).isFalse();
        assertThat(audit.covers(index("i", false, "c", "a"), List.of("a")))
                .isFalse();
    }

    @Test
    void testCovers_IndexNarrowerThanFk_DoesNotCover() {
        assertThat(audit.covers(index("i", false, "a"), List.of("a", "b")))
                .isFalse();
    }

    @Test
    void testCovers_PartialIndex_DoesNotCover() {
        assertThat(audit.covers(index("i", true, "a"), List.of("a"))).isFalse();
    }

    @Test
    void testCovers_ExpressionColumnInLeadingBlock_DoesNotCover() {
        final IndexDefinition expressionLeading = new IndexDefinition("child",
                "i", false, false, false, Arrays.asList(null, "a"));
        assertThat(audit.covers(expressionLeading, List.of("a"))).isFalse();
    }

    // --- audit(...) with mocked catalog reads ---

    private static Map<String, @Nullable Object> fkRow(final String constraint,
            final String column) {
        final Map<String, @Nullable Object> row =
                new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        row.put("table_name", "child");
        row.put("constraint_name", constraint);
        row.put("referenced_table", "parent");
        row.put("column_name", column);
        return row;
    }

    @Test
    void testAudit_UnindexedCompositeFk_ReportsFkAndColumns() {
        final CatalogQueries catalogQueries = mock(CatalogQueries.class);
        final IndexCatalog indexCatalog = mock(IndexCatalog.class);
        final var auditUnderTest = new ForeignKeyIndexAudit(catalogQueries,
                indexCatalog, DatabasePlatform.POSTGRESQL);
        when(catalogQueries.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(fkRow("fk_child_parent", "a"),
                        fkRow("fk_child_parent", "b")));
        when(indexCatalog.readAll("public"))
                .thenReturn(List.of(index("only_c", false, "c")));

        assertThat(auditUnderTest.audit("public", Set.of()))
                .as("Unindexed composite FK should be reported.")
                .anySatisfy(violation -> assertThat(violation)
                        .contains("child.fk_child_parent")
                        .contains("FOREIGN KEY (a, b) REFERENCES parent"));
    }

    @Test
    void testAudit_IndexCoversFk_ReturnsNoViolations() {
        final CatalogQueries catalogQueries = mock(CatalogQueries.class);
        final IndexCatalog indexCatalog = mock(IndexCatalog.class);
        final var auditUnderTest = new ForeignKeyIndexAudit(catalogQueries,
                indexCatalog, DatabasePlatform.POSTGRESQL);
        when(catalogQueries.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(fkRow("fk_child_parent", "a")));
        when(indexCatalog.readAll("public"))
                .thenReturn(List.of(index("idx_a", false, "a", "x")));

        assertThat(auditUnderTest.audit("public", Set.of()))
                .as("FK covered by an index should produce no violation.")
                .isEmpty();
    }

    @Test
    void testAudit_ExcludedConstraint_ReturnsNoViolations() {
        final CatalogQueries catalogQueries = mock(CatalogQueries.class);
        final IndexCatalog indexCatalog = mock(IndexCatalog.class);
        final var auditUnderTest = new ForeignKeyIndexAudit(catalogQueries,
                indexCatalog, DatabasePlatform.POSTGRESQL);
        when(catalogQueries.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(fkRow("fk_child_parent", "a")));
        when(indexCatalog.readAll("public"))
                .thenReturn(List.of(index("only_c", false, "c")));

        assertThat(auditUnderTest.audit("public", Set.of("fk_child_parent")))
                .as("Excluded constraint should not produce a violation.")
                .isEmpty();
    }
}
