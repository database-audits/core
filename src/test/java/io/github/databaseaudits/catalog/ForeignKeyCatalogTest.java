package io.github.databaseaudits.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import io.github.databaseaudits.jdbc.CatalogQueries;
import io.github.databaseaudits.platform.DatabasePlatform;

/**
 * Pure unit tests of the catalog reader's SQL routing and row grouping with
 * hand-built rows; the injected {@link CatalogQueries} is an inert mock. The
 * end-to-end path is covered by {@link io.github.databaseaudits.audit.catalog.CatalogAuditsIT}.
 */
class ForeignKeyCatalogTest {

    private final ForeignKeyCatalog catalog =
            catalogFor(DatabasePlatform.POSTGRESQL);

    private static ForeignKeyCatalog catalogFor(final DatabasePlatform platform) {
        return new ForeignKeyCatalog(mock(CatalogQueries.class), platform);
    }

    @Test
    void testSql_EachPlatform_RoutesToItsOwnCatalog() {
        assertThat(catalogFor(DatabasePlatform.POSTGRESQL).sql())
                .as("PostgreSQL should query pg_constraint.")
                .contains("pg_constraint");
        assertThat(catalogFor(DatabasePlatform.MYSQL).sql())
                .as("MySQL should query information_schema.key_column_usage.")
                .contains("information_schema.key_column_usage");
        assertThat(catalogFor(DatabasePlatform.MARIADB).sql())
                .as("MariaDB should use the same SQL as MySQL.")
                .isEqualTo(catalogFor(DatabasePlatform.MYSQL).sql());
        assertThat(catalogFor(DatabasePlatform.H2).sql())
                .as("H2 should query information_schema.referential_constraints.")
                .contains("information_schema.referential_constraints");
    }

    @Test
    void testFromRows_CompositeForeignKey_GroupsOneDefinitionWithOrderedColumnPairs() {
        final List<ForeignKeyDefinition> defs = catalog.fromRows(List.of(
                row("child", "fk_child_parent", "a", "parent", "x"),
                row("child", "fk_child_parent", "b", "parent", "y")));

        assertThat(defs)
                .as("A composite foreign key's rows should group into one definition with ordered column pairs.")
                .containsExactly(new ForeignKeyDefinition("child",
                        "fk_child_parent", "parent", List.of("a", "b"),
                        List.of("x", "y")));
    }

    @Test
    void testFromRows_TwoConstraintsOnSameTable_StaySeparate() {
        final List<ForeignKeyDefinition> defs = catalog.fromRows(List.of(
                row("child", "fk_child_parent", "parent_id", "parent", "id"),
                row("child", "fk_child_other", "other_id", "other", "id")));

        assertThat(defs)
                .as("Two distinct constraints on the same table should stay separate.")
                .containsExactly(
                        new ForeignKeyDefinition("child", "fk_child_parent",
                                "parent", List.of("parent_id"),
                                List.of("id")),
                        new ForeignKeyDefinition("child", "fk_child_other",
                                "other", List.of("other_id"), List.of("id")));
    }

    @Test
    void testFromRows_SameConstraintNameOnTwoTables_StaySeparate() {
        final List<ForeignKeyDefinition> defs = catalog.fromRows(List.of(
                row("child_a", "fk_parent", "parent_id", "parent", "id"),
                row("child_b", "fk_parent", "parent_id", "parent", "id")));

        assertThat(defs)
                .as("The same constraint name on two different tables should stay separate.")
                .containsExactly(
                        new ForeignKeyDefinition("child_a", "fk_parent",
                                "parent", List.of("parent_id"),
                                List.of("id")),
                        new ForeignKeyDefinition("child_b", "fk_parent",
                                "parent", List.of("parent_id"),
                                List.of("id")));
    }

    @Test
    void testReadAll_PlatformSql_QueriesThroughCatalogQueriesAndGroupsRows() {
        final CatalogQueries catalogQueries = mock(CatalogQueries.class);
        final ForeignKeyCatalog readingCatalog =
                new ForeignKeyCatalog(catalogQueries, DatabasePlatform.POSTGRESQL);
        when(catalogQueries.queryForList(readingCatalog.sql(), "public"))
                .thenReturn(List.of(
                        row("child", "fk_child_parent", "parent_id", "parent",
                                "id")));

        assertThat(readingCatalog.readAll("public"))
                .as("readAll should query through CatalogQueries and group the resulting rows.")
                .containsExactly(new ForeignKeyDefinition("child",
                        "fk_child_parent", "parent", List.of("parent_id"),
                        List.of("id")));
    }

    @Test
    void testFromRows_UpperCasedColumnLabels_MatchesCaseInsensitively() {
        final Map<String, @Nullable Object> upperCased =
                new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        upperCased.put("TABLE_NAME", "child");
        upperCased.put("CONSTRAINT_NAME", "fk_child_parent");
        upperCased.put("COLUMN_NAME", "parent_id");
        upperCased.put("REFERENCED_TABLE", "parent");
        upperCased.put("REFERENCED_COLUMN", "id");

        assertThat(catalog.fromRows(List.of(upperCased)))
                .as("Upper-cased column labels should match the same as lower-case.")
                .containsExactly(new ForeignKeyDefinition("child",
                        "fk_child_parent", "parent", List.of("parent_id"),
                        List.of("id")));
    }

    private static Map<String, @Nullable Object> row(final String table,
            final String constraint, final String column,
            final String referencedTable, final String referencedColumn) {
        // built the way CatalogQueries builds rows: case-insensitive keys
        final Map<String, @Nullable Object> row =
                new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        row.put("table_name", table);
        row.put("constraint_name", constraint);
        row.put("column_name", column);
        row.put("referenced_table", referencedTable);
        row.put("referenced_column", referencedColumn);
        return row;
    }
}
