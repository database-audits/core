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
    private final CatalogQueries jdbcSupport;
    private final DatabasePlatform platform;

    /**
     * pg_catalog, not information_schema (which has no index views).
     * {@code indkey} entries of {@code 0} are expression parts; the LEFT JOIN
     * turns them into NULL column names. Invalid indexes (failed concurrent
     * builds) are skipped, as are INCLUDE columns ({@code > indnkeyatts}).
     */
    private static final String POSTGRESQL_SQL =
            """
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

    /**
     * A prefix part ({@code sub_part} set) indexes only the leading bytes of
     * the column, so it is mapped to NULL like an expression part — it cannot
     * be relied on to cover full-column lookups.
     */
    private static final String MYSQL_SQL =
            """
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

    private static final String H2_SQL = """
            SELECT ic.table_name        AS table_name,
                   ic.index_name        AS index_name,
                   (i.index_type_name = 'PRIMARY KEY'
                    OR i.index_type_name LIKE '%UNIQUE%') AS is_unique,
                   (i.index_type_name = 'PRIMARY KEY')    AS is_primary,
                   FALSE                AS is_partial,
                   ic.column_name       AS column_name
            FROM   information_schema.index_columns ic
            JOIN   information_schema.indexes i
              ON   i.index_schema = ic.index_schema
             AND   i.index_name   = ic.index_name
             AND   i.table_name   = ic.table_name
            WHERE  ic.table_schema = ?
              AND  i.index_type_name <> 'SPATIAL INDEX'
            ORDER  BY 1, 2, ic.ordinal_position
            """;

    /**
     * Returns every index of {@code schema} with its key columns in index
     * order, via the platform's catalog SQL.
     *
     * @param schema
     *                   The schema to read indexes from.
     * @return An immutable list of all indexes in the schema.
     */
    public List<IndexDefinition> readAll(final String schema) {
        return fromRows(jdbcSupport.queryForList(sql(), schema));
    }

    /**
     * Returns the platform-specific SQL that reads every index of a schema with
     * its key columns in index order.
     *
     * @return The platform-specific SQL string.
     */
    public String sql() {
        return switch (platform) {
        case POSTGRESQL -> POSTGRESQL_SQL;
        case MYSQL, MARIADB -> MYSQL_SQL;
        case H2 -> H2_SQL;
        };
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
