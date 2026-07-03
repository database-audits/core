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
 * Pure unit tests of the audit's mismatch logic and SQL routing with hand-built
 * rows; the injected {@link CatalogQueries} is an inert mock. The end-to-end path
 * is covered by {@link CatalogAuditsIT}.
 */
class ForeignKeyTypeMatchAuditTest {

    private static ForeignKeyTypeMatchAudit auditFor(
            final DatabasePlatform platform) {
        return new ForeignKeyTypeMatchAudit(mock(CatalogQueries.class), platform);
    }

    private static Map<String, @Nullable Object> typeRow(final String column,
            final String columnType, final String referencedColumn,
            final String referencedType) {
        final Map<String, @Nullable Object> row =
                new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        row.put("table_name", "child");
        row.put("constraint_name", "fk_child_parent");
        row.put("column_name", column);
        row.put("column_type", columnType);
        row.put("referenced_table", "parent");
        row.put("referenced_column", referencedColumn);
        row.put("referenced_type", referencedType);
        return row;
    }

    @Test
    void testSql_EachPlatform_RoutesToItsOwnCatalog() {
        assertThat(auditFor(DatabasePlatform.POSTGRESQL).sql())
                .contains("pg_constraint").contains("format_type");
        assertThat(auditFor(DatabasePlatform.MYSQL).sql())
                .contains("referenced_column_name").contains("column_type");
        assertThat(auditFor(DatabasePlatform.MARIADB).sql())
                .isEqualTo(auditFor(DatabasePlatform.MYSQL).sql());
        assertThat(auditFor(DatabasePlatform.H2).sql())
                .contains("position_in_unique_constraint");
    }

    @Test
    void testIsMismatched_DifferingDeclaredTypes_True() {
        final var audit = auditFor(DatabasePlatform.POSTGRESQL);
        assertThat(audit
                .isMismatched(typeRow("parent_id", "integer", "id", "bigint")))
                .as("integer vs bigint should be a mismatch.")
                .isTrue();
        assertThat(audit.isMismatched(typeRow("code", "character varying(20)",
                "code", "character varying(10)")))
                .as("varchar(20) vs varchar(10) should be a mismatch.")
                .isTrue();
    }

    @Test
    void testIsMismatched_SameDeclaredTypeAnyCase_False() {
        final var audit = auditFor(DatabasePlatform.POSTGRESQL);
        assertThat(audit
                .isMismatched(typeRow("parent_id", "bigint", "id", "bigint")))
                .as("Same declared type should not be a mismatch.")
                .isFalse();
        assertThat(audit
                .isMismatched(typeRow("parent_id", "BIGINT", "id", "bigint")))
                .as("Same type in different case should not be a mismatch.")
                .isFalse();
    }

    @Test
    void testAudit_MismatchedFkColumnType_ReportsColumnAndTypes() {
        final CatalogQueries catalogQueries = mock(CatalogQueries.class);
        final var audit = new ForeignKeyTypeMatchAudit(catalogQueries,
                DatabasePlatform.POSTGRESQL);
        when(catalogQueries.queryForList(anyString(), any(Object[].class)))
                .thenReturn(
                        List.of(typeRow("parent_id", "integer", "id", "bigint"),
                                typeRow("other_id", "bigint", "id", "bigint")));

        assertThat(audit.audit("public", Set.of()))
                .as("Mismatched FK column type should be reported.")
                .anySatisfy(violation -> assertThat(violation).contains(
                        "child.parent_id is integer but references parent.id which is bigint")
                        .contains("fk_child_parent"))
                .noneSatisfy(violation -> assertThat(violation)
                        .contains("other_id"));
    }

    @Test
    void testAudit_ExcludedColumn_ReturnsNoViolations() {
        final CatalogQueries catalogQueries = mock(CatalogQueries.class);
        final var audit = new ForeignKeyTypeMatchAudit(catalogQueries,
                DatabasePlatform.POSTGRESQL);
        when(catalogQueries.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List
                        .of(typeRow("parent_id", "integer", "id", "bigint")));

        assertThat(audit.audit("public", Set.of("child.parent_id")))
                .as("Excluded column should produce no violations.")
                .isEmpty();
    }

    @Test
    void testAudit_MatchingTypesOnly_ReturnsNoViolations() {
        final CatalogQueries catalogQueries = mock(CatalogQueries.class);
        final var audit = new ForeignKeyTypeMatchAudit(catalogQueries,
                DatabasePlatform.POSTGRESQL);
        when(catalogQueries.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List
                        .of(typeRow("parent_id", "bigint", "id", "bigint")));

        assertThat(audit.audit("public", Set.of()))
                .as("Matching types should produce no violations.")
                .isEmpty();
    }
}
