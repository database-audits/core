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

    /**
     * pg_catalog pairs each FK column with its referenced column positionally
     * via {@code conkey}/{@code confkey}; {@code format_type} renders the full
     * declared type (with modifiers, e.g. {@code character varying(10)}).
     */
    private static final String POSTGRESQL_FK_COLUMN_TYPES_SQL =
            """
                    SELECT cl.relname                             AS table_name,
                           c.conname                              AS constraint_name,
                           a.attname                              AS column_name,
                           format_type(a.atttypid, a.atttypmod)   AS column_type,
                           ref.relname                            AS referenced_table,
                           ra.attname                             AS referenced_column,
                           format_type(ra.atttypid, ra.atttypmod) AS referenced_type
                    FROM   pg_constraint c
                    JOIN   pg_class cl  ON cl.oid  = c.conrelid
                    JOIN   pg_class ref ON ref.oid = c.confrelid
                    CROSS  JOIN LATERAL unnest(c.conkey, c.confkey)
                           WITH ORDINALITY AS k(attnum, refattnum, ordinal)
                    JOIN   pg_attribute a  ON a.attrelid  = c.conrelid  AND a.attnum  = k.attnum
                    JOIN   pg_attribute ra ON ra.attrelid = c.confrelid AND ra.attnum = k.refattnum
                    WHERE  c.contype = 'f'
                      AND  c.connamespace = ?::regnamespace
                    ORDER  BY 1, 2, k.ordinal
                    """;

    /**
     * key_column_usage carries the referenced table and column directly on
     * MySQL/MariaDB; {@code column_type} is the full declared type including
     * length and signedness (e.g. {@code varchar(10)},
     * {@code bigint unsigned}).
     */
    private static final String MYSQL_FK_COLUMN_TYPES_SQL = """
            SELECT k.table_name             AS table_name,
                   k.constraint_name        AS constraint_name,
                   k.column_name            AS column_name,
                   col.column_type          AS column_type,
                   k.referenced_table_name  AS referenced_table,
                   k.referenced_column_name AS referenced_column,
                   rcol.column_type         AS referenced_type
            FROM   information_schema.key_column_usage k
            JOIN   information_schema.columns col
              ON   col.table_schema = k.table_schema
             AND   col.table_name   = k.table_name
             AND   col.column_name  = k.column_name
            JOIN   information_schema.columns rcol
              ON   rcol.table_schema = k.referenced_table_schema
             AND   rcol.table_name   = k.referenced_table_name
             AND   rcol.column_name  = k.referenced_column_name
            WHERE  k.table_schema = ?
              AND  k.referenced_table_name IS NOT NULL
            ORDER  BY 1, 2, k.ordinal_position
            """;

    /**
     * Standard information_schema: {@code position_in_unique_constraint} maps
     * each FK column to the referenced unique/PK constraint's column at that
     * position. The declared type is composed from {@code data_type} plus the
     * character length when present ({@code '(' || NULL || ')'} concatenates to
     * NULL, so COALESCE drops it).
     */
    private static final String H2_FK_COLUMN_TYPES_SQL =
            """
                    SELECT tc.table_name      AS table_name,
                           tc.constraint_name AS constraint_name,
                           kcu.column_name    AS column_name,
                           col.data_type  || COALESCE('(' || col.character_maximum_length  || ')', '') AS column_type,
                           ref_kcu.table_name  AS referenced_table,
                           ref_kcu.column_name AS referenced_column,
                           rcol.data_type || COALESCE('(' || rcol.character_maximum_length || ')', '') AS referenced_type
                    FROM   information_schema.table_constraints tc
                    JOIN   information_schema.key_column_usage kcu
                      ON   kcu.constraint_schema = tc.constraint_schema
                     AND   kcu.constraint_name   = tc.constraint_name
                     AND   kcu.table_name        = tc.table_name
                    JOIN   information_schema.referential_constraints rc
                      ON   rc.constraint_schema = tc.constraint_schema
                     AND   rc.constraint_name   = tc.constraint_name
                    JOIN   information_schema.key_column_usage ref_kcu
                      ON   ref_kcu.constraint_schema = rc.unique_constraint_schema
                     AND   ref_kcu.constraint_name   = rc.unique_constraint_name
                     AND   ref_kcu.ordinal_position  = kcu.position_in_unique_constraint
                    JOIN   information_schema.columns col
                      ON   col.table_schema = kcu.table_schema
                     AND   col.table_name   = kcu.table_name
                     AND   col.column_name  = kcu.column_name
                    JOIN   information_schema.columns rcol
                      ON   rcol.table_schema = ref_kcu.table_schema
                     AND   rcol.table_name   = ref_kcu.table_name
                     AND   rcol.column_name  = ref_kcu.column_name
                    WHERE  tc.constraint_type = 'FOREIGN KEY'
                      AND  tc.table_schema = ?
                    ORDER  BY 1, 2, kcu.ordinal_position
                    """;

    String sql() {
        return switch (platform) {
        case POSTGRESQL -> POSTGRESQL_FK_COLUMN_TYPES_SQL;
        case MYSQL, MARIADB -> MYSQL_FK_COLUMN_TYPES_SQL;
        case H2 -> H2_FK_COLUMN_TYPES_SQL;
        };
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
