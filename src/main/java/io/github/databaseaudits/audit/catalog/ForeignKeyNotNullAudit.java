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
    private final CatalogQueries jdbcSupport;
    private final DatabasePlatform platform;

    /**
     * Standard information_schema, valid as-is on PostgreSQL, MySQL, MariaDB,
     * and H2. The join includes {@code table_name} because constraint names are
     * only unique per table on PostgreSQL and MySQL.
     */
    private static final String INFORMATION_SCHEMA_NULLABLE_FK_COLUMN_SQL = """
            SELECT kcu.table_name      AS table_name,
                   kcu.constraint_name AS constraint_name,
                   kcu.column_name     AS column_name
            FROM   information_schema.table_constraints tc
            JOIN   information_schema.key_column_usage kcu
              ON   kcu.constraint_schema = tc.constraint_schema
             AND   kcu.constraint_name   = tc.constraint_name
             AND   kcu.table_name        = tc.table_name
            JOIN   information_schema.columns col
              ON   col.table_schema = kcu.table_schema
             AND   col.table_name   = kcu.table_name
             AND   col.column_name  = kcu.column_name
            WHERE  tc.constraint_type = 'FOREIGN KEY'
              AND  tc.table_schema = ?
              AND  col.is_nullable = 'YES'
            ORDER  BY 1, 2, 3
            """;

    String sql() {
        return switch (platform) {
        case POSTGRESQL, MYSQL, MARIADB, H2 ->
            INFORMATION_SCHEMA_NULLABLE_FK_COLUMN_SQL;
        };
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
        return jdbcSupport.queryForList(sql(), schema).stream()
                .filter(r -> !excludedColumns.contains(
                        r.get("table_name") + "." + r.get("column_name")))
                .map(r -> "%s.%s (%s) is nullable".formatted(
                        r.get("table_name"), r.get("column_name"),
                        r.get("constraint_name")))
                .toList();
    }
}
