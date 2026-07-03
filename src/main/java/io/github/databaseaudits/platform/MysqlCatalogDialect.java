package io.github.databaseaudits.platform;

/**
 * The {@link CatalogDialect} for MySQL, reading from {@code information_schema}.
 * {@link DatabasePlatform#MARIADB} reuses it — the catalog SQL is identical; a
 * future MariaDB divergence becomes one overriding subclass.
 */
public final class MysqlCatalogDialect implements CatalogDialect {
    /**
     * A prefix part ({@code sub_part} set) indexes only the leading bytes of
     * the column, so it is mapped to NULL like an expression part — it cannot
     * be relied on to cover full-column lookups.
     */
    @Override
    public String indexCatalogSql() {
        return """
                SELECT s.table_name               AS table_name,
                       s.index_name               AS index_name,
                       (s.non_unique = 0)         AS is_unique,
                       (s.index_name = 'PRIMARY') AS is_primary,
                       FALSE                      AS is_partial,
                       CASE WHEN s.sub_part IS NULL THEN s.column_name END AS column_name
                FROM   information_schema.statistics s
                WHERE  s.table_schema = ?
                  AND  s.index_type NOT IN ('FULLTEXT', 'SPATIAL')
                ORDER  BY 1, 2, s.seq_in_index
                """;
    }

    /**
     * key_column_usage carries the referenced table directly on MySQL/MariaDB.
     */
    @Override
    public String foreignKeysSql() {
        return """
                SELECT k.table_name            AS table_name,
                       k.constraint_name       AS constraint_name,
                       k.referenced_table_name AS referenced_table,
                       k.column_name           AS column_name
                FROM   information_schema.key_column_usage k
                WHERE  k.table_schema = ?
                  AND  k.referenced_table_name IS NOT NULL
                ORDER  BY 1, 2, k.ordinal_position
                """;
    }

    /**
     * key_column_usage carries the referenced table and column directly on
     * MySQL/MariaDB; {@code column_type} is the full declared type including
     * length and signedness (e.g. {@code varchar(10)}, {@code bigint unsigned}).
     */
    @Override
    public String foreignKeyColumnTypesSql() {
        return """
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
    }
}
