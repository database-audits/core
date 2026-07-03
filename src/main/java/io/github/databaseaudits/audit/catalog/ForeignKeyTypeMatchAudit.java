package io.github.databaseaudits.audit.catalog;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import io.github.databaseaudits.jdbc.CatalogQueries;
import io.github.databaseaudits.platform.DatabasePlatform;
import lombok.AllArgsConstructor;

/**
 * Every foreign key column should have exactly the declared type of the column
 * it references.
 *
 * <p>
 * A mismatched pair — an {@code integer} column referencing a {@code bigint}
 * key, differing {@code varchar} lengths — forces conversions in joins and FK
 * checks, can defeat index use, and caps the child at the narrower range long
 * after the parent outgrows it. PostgreSQL permits such FKs outright;
 * MySQL/MariaDB reject integer-width mismatches but permit differing string
 * lengths. Advisory: pass deliberate mismatches (as {@code table.column}) as
 * {@code excludedColumns}. Composite FKs are reported per column.
 * Catalog-driven, deterministic; supports every {@link DatabasePlatform}.
 *
 * <p>
 * Fix: align the FK column's type with its referenced column's, or exclude the
 * column if the mismatch is deliberate.
 */
@AllArgsConstructor
public class ForeignKeyTypeMatchAudit {
    private final CatalogQueries catalogQueries;
    private final DatabasePlatform platform;

    String sql() {
        return platform.catalogDialect().foreignKeyColumnTypesSql();
    }

    /**
     * Returns a description of every foreign key column whose declared type
     * differs from its referenced column's, except the excluded ones; an empty
     * list when every FK column matches.
     *
     * @param schema
     *                            The schema to scan.
     * @param excludedColumns
     *                            The columns to skip, as {@code table.column}.
     * @return One description per foreign key column whose type differs from
     *         its referenced column's; an empty list when every column matches.
     */
    public List<String> audit(final String schema,
            final Set<String> excludedColumns) {
        return catalogQueries.queryForList(sql(), schema).stream()
                .filter(this::isMismatched)
                .filter(r -> !excludedColumns.contains(
                        r.get("table_name") + "." + r.get("column_name")))
                .map(r -> "%s.%s is %s but references %s.%s which is %s (%s)"
                        .formatted(r.get("table_name"), r.get("column_name"),
                                r.get("column_type"), r.get("referenced_table"),
                                r.get("referenced_column"),
                                r.get("referenced_type"),
                                r.get("constraint_name")))
                .toList();
    }

    /**
     * Whether the FK column's declared type differs from its referenced
     * column's (case-insensitively).
     */
    boolean isMismatched(final Map<String, @Nullable Object> row) {
        final String columnType = String.valueOf(row.get("column_type"));
        final String referencedType =
                String.valueOf(row.get("referenced_type"));
        return !columnType.equalsIgnoreCase(referencedType);
    }
}
