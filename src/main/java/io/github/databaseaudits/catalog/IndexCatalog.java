package io.github.databaseaudits.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.github.databaseaudits.jdbc.CatalogQueries;
import io.github.databaseaudits.platform.DatabasePlatform;
import lombok.AllArgsConstructor;

/**
 * Reads every index of a schema — with its key columns in index order — from
 * the platform's catalog, as {@link IndexDefinition} value records: the shared
 * building block. An injected collaborator, not a static utility: public so any
 * container can construct it, with package-private methods only the audits use.
 *
 * <p>
 * The per-platform SQL stays a deliberately flat projection (one row per key
 * column, ordered); the leading-prefix comparisons live in the audits' Java,
 * where they are unit-testable and platform-independent. Only key columns are
 * read (PostgreSQL {@code INCLUDE} columns are excluded); full-text/spatial
 * indexes are excluded on every platform.
 */
@AllArgsConstructor
public class IndexCatalog {
    private final CatalogQueries catalogQueries;
    private final DatabasePlatform platform;

    /**
     * Returns every index of {@code schema} with its key columns in index
     * order, via the platform's catalog SQL.
     *
     * @param schema
     *                   The schema to read indexes from.
     * @return An immutable list of all indexes in the schema.
     */
    public List<IndexDefinition> readAll(final String schema) {
        return fromRows(catalogQueries.queryForList(sql(), schema));
    }

    /**
     * Returns the platform-specific SQL that reads every index of a schema with
     * its key columns in index order.
     *
     * @return The platform-specific SQL string.
     */
    public String sql() {
        return platform.catalogDialect().indexCatalogSql();
    }

    /**
     * Groups the flat rows — one per key column, already ordered by table,
     * index, and column position — into one definition per index. Flag columns
     * may arrive as BOOLEAN (PostgreSQL, H2) or as 0/1 numbers (MySQL,
     * MariaDB).
     *
     * @param rows
     *                 The flat projection rows from the catalog query.
     * @return An immutable list of index definitions.
     */
    public List<IndexDefinition> fromRows(
            final List<Map<String, @Nullable Object>> rows) {
        final var byIndex = new LinkedHashMap<String, IndexDefinition>();
        for (final Map<String, @Nullable Object> row : rows) {
            final String table = String.valueOf(row.get("table_name"));
            final String index = String.valueOf(row.get("index_name"));
            byIndex.computeIfAbsent(table + ' ' + index,
                    key -> new IndexDefinition(table, index,
                            asBoolean(row.get("is_unique")),
                            asBoolean(row.get("is_primary")),
                            asBoolean(row.get("is_partial")),
                            new ArrayList<>()))
                    .columns().add((String) row.get("column_name"));
        }
        return List.copyOf(byIndex.values());
    }

    private boolean asBoolean(final @Nullable Object value) {
        if (value instanceof final Boolean bool) {
            return bool;
        }
        return value instanceof final Number number && number.longValue() != 0;
    }
}
