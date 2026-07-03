package io.github.databaseaudits.platform;

/**
 * The {@link CatalogDialect} for PostgreSQL, reading from {@code pg_catalog}.
 */
public final class PostgresqlCatalogDialect implements CatalogDialect {
    /**
     * pg_catalog, not information_schema (which has no index views).
     * {@code indkey} entries of {@code 0} are expression parts; the LEFT JOIN
     * turns them into NULL column names. Invalid indexes (failed concurrent
     * builds) are skipped, as are INCLUDE columns ({@code > indnkeyatts}).
     */
    @Override
    public String indexCatalogSql() {
        return """
                SELECT t.relname               AS table_name,
                       ic.relname              AS index_name,
                       i.indisunique           AS is_unique,
                       i.indisprimary          AS is_primary,
                       (i.indpred IS NOT NULL) AS is_partial,
                       a.attname               AS column_name
                FROM   pg_index i
                JOIN   pg_class t  ON t.oid  = i.indrelid
                JOIN   pg_class ic ON ic.oid = i.indexrelid
                CROSS  JOIN LATERAL unnest(string_to_array(i.indkey::text, ' ')::int2[])
                       WITH ORDINALITY AS k(attnum, ordinal)
                LEFT   JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum
                WHERE  t.relnamespace = ?::regnamespace
                  AND  i.indisvalid
                  AND  k.ordinal <= i.indnkeyatts
                ORDER  BY 1, 2, k.ordinal
                """;
    }

    @Override
    public String foreignKeysSql() {
        return """
                SELECT cl.relname  AS table_name,
                       c.conname   AS constraint_name,
                       ref.relname AS referenced_table,
                       a.attname   AS column_name
                FROM   pg_constraint c
                JOIN   pg_class cl  ON cl.oid  = c.conrelid
                JOIN   pg_class ref ON ref.oid = c.confrelid
                CROSS  JOIN LATERAL unnest(c.conkey) WITH ORDINALITY AS k(attnum, ordinal)
                JOIN   pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum
                WHERE  c.contype = 'f'
                  AND  c.connamespace = ?::regnamespace
                ORDER  BY 1, 2, k.ordinal
                """;
    }

    /**
     * pg_catalog pairs each FK column with its referenced column positionally
     * via {@code conkey}/{@code confkey}; {@code format_type} renders the full
     * declared type (with modifiers, e.g. {@code character varying(10)}).
     */
    @Override
    public String foreignKeyColumnTypesSql() {
        return """
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
    }
}
