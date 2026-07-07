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
 * Reads every foreign key of a schema — with its columns and referenced
 * columns in constraint order — from the platform's catalog, as
 * {@link ForeignKeyDefinition} value records: the shared building block. An
 * injected collaborator, not a static utility: public so any container can
 * construct it, with package-private methods only the audits use.
 *
 * <p>
 * Reuses {@link io.github.databaseaudits.platform.CatalogDialect#foreignKeyColumnTypesSql()}
 * — already a flat one-row-per-column projection of {@code table_name},
 * {@code constraint_name}, {@code column_name}, {@code referenced_table}, and
 * {@code referenced_column}, ordered by table, constraint, and ordinal on every
 * engine — rather than duplicating a second per-engine query.
 */
@AllArgsConstructor
public class ForeignKeyCatalog {
    private final CatalogQueries catalogQueries;
    private final DatabasePlatform platform;

    /**
     * Returns every foreign key of {@code schema} with its columns and
     * referenced columns in constraint order, via the platform's catalog SQL.
     *
     * @param schema
     *                   The schema to read foreign keys from.
     * @return An immutable list of all foreign keys in the schema.
     */
    public List<ForeignKeyDefinition> readAll(final String schema) {
        return fromRows(catalogQueries.queryForList(sql(), schema));
    }

    /**
     * Returns the platform-specific SQL that reads every foreign key column of
     * a schema paired with its referenced column.
     *
     * @return The platform-specific SQL string.
     */
    public String sql() {
        return platform.catalogDialect().foreignKeyColumnTypesSql();
    }

    /**
     * Groups the flat rows — one per FK column, already ordered by table,
     * constraint, and ordinal position — into one definition per constraint.
     *
     * @param rows
     *                 The flat projection rows from the catalog query.
     * @return An immutable list of foreign key definitions.
     */
    public List<ForeignKeyDefinition> fromRows(
            final List<Map<String, @Nullable Object>> rows) {
        final var byConstraint = new LinkedHashMap<String, ForeignKeyDefinition>();
        for (final Map<String, @Nullable Object> row : rows) {
            final String table = String.valueOf(row.get("table_name"));
            final String constraint =
                    String.valueOf(row.get("constraint_name"));
            final ForeignKeyDefinition definition =
                    byConstraint.computeIfAbsent(table + ' ' + constraint,
                            key -> new ForeignKeyDefinition(table, constraint,
                                    String.valueOf(row.get("referenced_table")),
                                    new ArrayList<>(), new ArrayList<>()));
            definition.columns().add(String.valueOf(row.get("column_name")));
            definition.referencedColumns()
                    .add(String.valueOf(row.get("referenced_column")));
        }
        return List.copyOf(byConstraint.values());
    }
}
