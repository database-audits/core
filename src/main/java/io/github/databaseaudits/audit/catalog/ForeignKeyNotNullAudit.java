package io.github.databaseaudits.audit.catalog;

import java.util.List;
import java.util.Set;

import io.github.databaseaudits.jdbc.CatalogQueries;
import io.github.databaseaudits.platform.DatabasePlatform;
import lombok.AllArgsConstructor;

/**
 * Every foreign key column should be {@code NOT NULL} — unless the relationship
 * is genuinely optional.
 *
 * <p>
 * A nullable FK is occasionally correct, but far more often an oversight: a
 * logically mandatory {@code @ManyToOne} whose column was never made
 * {@code NOT NULL}, so the database silently permits parent-less rows.
 * Advisory: pass intentionally-nullable columns (as {@code table.column}) as
 * {@code excludedColumns}. Composite FKs are reported per column.
 * Catalog-driven, deterministic; supports every {@link DatabasePlatform}.
 *
 * <p>
 * Fix: make each column {@code NOT NULL}, or exclude it if the relationship is
 * genuinely optional.
 */
@AllArgsConstructor
public class ForeignKeyNotNullAudit {
    private final CatalogQueries catalogQueries;
    private final DatabasePlatform platform;

    String sql() {
        return platform.catalogDialect().nullableForeignKeyColumnSql();
    }

    /**
     * Returns a description of every nullable foreign key column, except the
     * excluded ones; an empty list when every FK column is {@code NOT NULL}.
     *
     * @param schema
     *                            The schema to scan.
     * @param excludedColumns
     *                            The columns to skip, as {@code table.column}.
     * @return One description per nullable foreign key column; an empty list
     *         when every foreign key column is {@code NOT NULL}.
     */
    public List<String> audit(final String schema,
            final Set<String> excludedColumns) {
        return catalogQueries.queryForList(sql(), schema).stream()
                .filter(r -> !excludedColumns.contains(
                        r.get("table_name") + "." + r.get("column_name")))
                .map(r -> "%s.%s (%s) is nullable".formatted(
                        r.get("table_name"), r.get("column_name"),
                        r.get("constraint_name")))
                .toList();
    }
}
