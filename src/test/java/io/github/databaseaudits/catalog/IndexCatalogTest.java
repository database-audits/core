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
 * end-to-end path is covered by {@link CatalogAuditsIT}.
 */
class IndexCatalogTest {

    private final IndexCatalog catalog =
            catalogFor(DatabasePlatform.POSTGRESQL);

    private static IndexCatalog catalogFor(final DatabasePlatform platform) {
        return new IndexCatalog(mock(CatalogQueries.class), platform);
    }

    @Test
    void testSql_EachPlatform_RoutesToItsOwnCatalog() {
        assertThat(catalogFor(DatabasePlatform.POSTGRESQL).sql())
                .contains("pg_index");
        assertThat(catalogFor(DatabasePlatform.MYSQL).sql())
                .contains("information_schema.statistics");
        assertThat(catalogFor(DatabasePlatform.MARIADB).sql())
                .isEqualTo(catalogFor(DatabasePlatform.MYSQL).sql());
        assertThat(catalogFor(DatabasePlatform.H2).sql())
                .contains("information_schema.index_columns");
    }

    @Test
    void testFromRows_OrderedPerColumnRows_GroupsOneDefinitionPerIndexInColumnOrder() {
        final List<IndexDefinition> defs = catalog.fromRows(
                List.of(row("orders", "orders_pkey", true, true, false, "id"),
                        row("orders", "orders_customer_idx", false, false,
                                false, "customer_id"),
                        row("orders", "orders_customer_idx", false, false,
                                false, "created_at")));

        assertThat(defs).containsExactly(
                new IndexDefinition("orders", "orders_pkey", true, true, false,
                        List.of("id")),
                new IndexDefinition("orders", "orders_customer_idx", false,
                        false, false, List.of("customer_id", "created_at")));
    }

    @Test
    void testFromRows_NumericFlagsAndNullColumns_ReadsFlagsAndExpressionColumn() {
        final List<IndexDefinition> defs =
                catalog.fromRows(List.of(row("t", "expr_idx", 1L, 0L, 0L, null),
                        row("t", "expr_idx", 1L, 0L, 0L, "a")));

        final IndexDefinition def = defs.getFirst();
        assertThat(def.unique()).isTrue();
        assertThat(def.primary()).isFalse();
        assertThat(def.partial()).isFalse();
        assertThat(def.columns()).containsExactly(null, "a");
        assertThat(def.hasExpressionColumn()).isTrue();
    }

    @Test
    void testReadAll_PlatformSql_QueriesThroughJdbcSupportAndGroupsRows() {
        final CatalogQueries jdbcSupport = mock(CatalogQueries.class);
        final IndexCatalog readingCatalog =
                new IndexCatalog(jdbcSupport, DatabasePlatform.POSTGRESQL);
        when(jdbcSupport.queryForList(readingCatalog.sql(), "public"))
                .thenReturn(List.of(row("t", "i", true, false, false, "a")));

        assertThat(readingCatalog.readAll("public"))
                .containsExactly(new IndexDefinition("t", "i", true, false,
                        false, List.of("a")));
    }

    @Test
    void testFromRows_NonBooleanNonNumericFlag_ReadsAsFalse() {
        assertThat(
                catalog.fromRows(List.of(row("t", "i", null, "yes", 0L, "a"))))
                .containsExactly(new IndexDefinition("t", "i", false, false,
                        false, List.of("a")));
    }

    @Test
    void testFromRows_UpperCasedColumnLabels_MatchesCaseInsensitively() {
        final Map<String, @Nullable Object> upperCased =
                new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        upperCased.put("TABLE_NAME", "t");
        upperCased.put("INDEX_NAME", "i");
        upperCased.put("IS_UNIQUE", Boolean.FALSE);
        upperCased.put("IS_PRIMARY", Boolean.FALSE);
        upperCased.put("IS_PARTIAL", Boolean.FALSE);
        upperCased.put("COLUMN_NAME", "c");

        assertThat(catalog.fromRows(List.of(upperCased)))
                .containsExactly(new IndexDefinition("t", "i", false, false,
                        false, List.of("c")));
    }

    private static Map<String, @Nullable Object> row(final String table,
            final String index, final Object unique, final Object primary,
            final Object partial, final @Nullable String column) {
        // built the way CatalogQueries builds rows: case-insensitive keys
        final Map<String, @Nullable Object> row =
                new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        row.put("table_name", table);
        row.put("index_name", index);
        row.put("is_unique", unique);
        row.put("is_primary", primary);
        row.put("is_partial", partial);
        row.put("column_name", column);
        return row;
    }
}
