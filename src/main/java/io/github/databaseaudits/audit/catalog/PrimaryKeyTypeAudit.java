package io.github.databaseaudits.audit.catalog;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;

import io.github.databaseaudits.audit.finding.Finding;
import io.github.databaseaudits.audit.finding.NarrowPrimaryKeyFinding;
import io.github.databaseaudits.jdbc.CatalogQueries;
import io.github.databaseaudits.platform.DatabasePlatform;
import lombok.AllArgsConstructor;

/**
 * Every primary key should be at least {@code bigint} wide.
 *
 * <p>
 * A 32-bit (or narrower) integer primary key overflows at roughly 2.1 billion
 * rows — a classic production outage that is trivial to fix at design time and
 * brutal to fix live, since widening a primary key in place typically means
 * rewriting the table and every referencing foreign key. Advisory for
 * genuinely small, bounded tables: pass their columns (as {@code table.column})
 * as {@code excludedColumns}. Catalog-driven, deterministic; supports every
 * {@link DatabasePlatform}.
 *
 * <p>
 * Fix: migrate the column to {@code BIGINT} — widening any referencing foreign
 * key columns in the same change, since {@link ForeignKeyTypeMatchAudit} will
 * flag stragglers — or exclude the column if the table is genuinely bounded.
 */
@AllArgsConstructor
public class PrimaryKeyTypeAudit {
    /**
     * Integer data types narrower than {@code bigint}, as reported by
     * {@code information_schema.columns.data_type} (lower case; matched
     * case-insensitively against the catalog's reported type).
     */
    static final Set<String> NARROW_INTEGER_TYPES =
            Set.of("int", "integer", "mediumint", "smallint", "tinyint");

    private final CatalogQueries catalogQueries;
    private final DatabasePlatform platform;

    String sql() {
        return platform.catalogDialect().primaryKeyColumnTypesSql();
    }

    /**
     * Returns one {@link Finding} for every primary key column declared
     * narrower than {@code bigint}, except the excluded ones; an empty list
     * when every primary key column is {@code bigint} or wider.
     *
     * @param schema
     *                            The schema to scan.
     * @param excludedColumns
     *                            The columns to skip, as {@code table.column},
     *                            matched case-insensitively (MySQL and MariaDB
     *                            report unquoted identifiers in upper case).
     * @return One {@link Finding} per narrow primary key column — its
     *         {@link Finding#description() description} is the reported line;
     *         an empty list when every primary key column is {@code bigint} or
     *         wider.
     */
    public List<Finding> audit(final String schema,
            final Set<String> excludedColumns) {
        final Set<String> excluded =
                new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        excluded.addAll(excludedColumns);
        return catalogQueries.queryForList(sql(), schema).stream()
                .filter(this::isNarrow)
                .filter(r -> !excluded.contains(
                        r.get("table_name") + "." + r.get("column_name")))
                .<Finding>map(r -> new NarrowPrimaryKeyFinding(
                        String.valueOf(r.get("table_name")),
                        String.valueOf(r.get("column_name")),
                        String.valueOf(r.get("data_type"))))
                .toList();
    }

    /**
     * Whether the row's declared data type is narrower than {@code bigint}
     * (case-insensitively).
     */
    boolean isNarrow(final Map<String, @Nullable Object> row) {
        final String dataType =
                String.valueOf(row.get("data_type")).toLowerCase(Locale.ROOT);
        return NARROW_INTEGER_TYPES.contains(dataType);
    }
}
