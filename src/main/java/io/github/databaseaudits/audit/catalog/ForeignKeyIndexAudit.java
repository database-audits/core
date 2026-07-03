package io.github.databaseaudits.audit.catalog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

import io.github.databaseaudits.catalog.IndexCatalog;
import io.github.databaseaudits.catalog.IndexDefinition;
import io.github.databaseaudits.jdbc.CatalogQueries;
import io.github.databaseaudits.platform.DatabasePlatform;
import lombok.AllArgsConstructor;

/**
 * Every foreign key must be backed by an index whose <em>leading</em> columns
 * are the FK columns.
 *
 * <p>
 * PostgreSQL and H2-without-referential-integrity do not auto-create an index
 * for a foreign key. A missing FK index means slow child→parent lookups and
 * lock-heavy parent {@code DELETE}/{@code UPDATE} (a sequential scan of the
 * child under a strong lock). On MySQL/MariaDB InnoDB auto-creates a supporting
 * index, so this audit normally passes there — on MariaDB it still catches an
 * index dropped after the fact (permitted while {@code foreign_key_checks} is
 * suspended; MySQL refuses such drops outright). Purely catalog-driven, so
 * deterministic regardless of test data; supports every
 * {@link DatabasePlatform}.
 *
 * <p>
 * Fix: add an index whose leading columns are the FK columns.
 */
@AllArgsConstructor
public class ForeignKeyIndexAudit {
    private final CatalogQueries catalogQueries;
    private final IndexCatalog indexCatalog;
    private final DatabasePlatform platform;

    /** One foreign key with its columns in constraint order. */
    record ForeignKey(String tableName, String constraintName,
            String referencedTable, List<String> columns) {
    }

    private static final String POSTGRESQL_FK_SQL =
            """
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

    /**
     * key_column_usage carries the referenced table directly on MySQL/MariaDB.
     */
    private static final String MYSQL_FK_SQL = """
            SELECT k.table_name            AS table_name,
                   k.constraint_name       AS constraint_name,
                   k.referenced_table_name AS referenced_table,
                   k.column_name           AS column_name
            FROM   information_schema.key_column_usage k
            WHERE  k.table_schema = ?
              AND  k.referenced_table_name IS NOT NULL
            ORDER  BY 1, 2, k.ordinal_position
            """;

    /**
     * Standard information_schema; constraint names are unique per schema on
     * H2, so the joins are exact.
     */
    private static final String H2_FK_SQL = """
            SELECT tc.table_name      AS table_name,
                   tc.constraint_name AS constraint_name,
                   ref_tc.table_name  AS referenced_table,
                   kcu.column_name    AS column_name
            FROM   information_schema.table_constraints tc
            JOIN   information_schema.key_column_usage kcu
              ON   kcu.constraint_schema = tc.constraint_schema
             AND   kcu.constraint_name   = tc.constraint_name
             AND   kcu.table_name        = tc.table_name
            JOIN   information_schema.referential_constraints rc
              ON   rc.constraint_schema = tc.constraint_schema
             AND   rc.constraint_name   = tc.constraint_name
            LEFT   JOIN information_schema.table_constraints ref_tc
              ON   ref_tc.constraint_schema = rc.unique_constraint_schema
             AND   ref_tc.constraint_name   = rc.unique_constraint_name
            WHERE  tc.constraint_type = 'FOREIGN KEY'
              AND  tc.table_schema = ?
            ORDER  BY 1, 2, kcu.ordinal_position
            """;

    String sql() {
        return switch (platform) {
        case POSTGRESQL -> POSTGRESQL_FK_SQL;
        case MYSQL, MARIADB -> MYSQL_FK_SQL;
        case H2 -> H2_FK_SQL;
        };
    }

    /**
     * Returns a description of every foreign key with no supporting index whose
     * leading columns are the FK columns, except excluded constraints; an empty
     * list when every FK is backed by one.
     *
     * @param schema
     *                                The schema to scan.
     * @param excludedConstraints
     *                                The constraint names to skip, e.g. a
     *                                join-table FK that is intentionally
     *                                unindexed.
     * @return One description per foreign key with no covering index; an empty
     *         list when every foreign key is backed by one.
     */
    public List<String> audit(final String schema,
            final Set<String> excludedConstraints) {
        final List<ForeignKey> foreignKeys = readForeignKeys(schema);
        final Map<String, List<IndexDefinition>> indexesByTable =
                indexCatalog.readAll(schema).stream().collect(
                        Collectors.groupingBy(IndexDefinition::tableName));
        return foreignKeys.stream().filter(
                fk -> !excludedConstraints.contains(fk.constraintName()))
                .filter(fk -> indexesByTable
                        .getOrDefault(fk.tableName(), List.of()).stream()
                        .noneMatch(index -> covers(index, fk.columns())))
                .map(fk -> "%s.%s  ->  FOREIGN KEY (%s) REFERENCES %s"
                        .formatted(fk.tableName(), fk.constraintName(),
                                String.join(", ", fk.columns()),
                                fk.referencedTable()))
                .toList();
    }

    /**
     * Whether the index's leading columns cover the FK columns in any order. A
     * partial index does not reliably support the FK, and an expression part
     * (null column) never matches.
     */
    boolean covers(final IndexDefinition index, final List<String> fkColumns) {
        return !index.partial() && index.columns().size() >= fkColumns.size()
                && new HashSet<>(index.columns().subList(0, fkColumns.size()))
                        .containsAll(fkColumns);
    }

    private List<ForeignKey> readForeignKeys(final String schema) {
        final List<Map<String, @Nullable Object>> rows =
                catalogQueries.queryForList(sql(), schema);
        final var byConstraint = new LinkedHashMap<String, ForeignKey>();
        for (final Map<String, @Nullable Object> row : rows) {
            final String table = String.valueOf(row.get("table_name"));
            final String constraint =
                    String.valueOf(row.get("constraint_name"));
            byConstraint
                    .computeIfAbsent(table + ' ' + constraint,
                            key -> new ForeignKey(table, constraint,
                                    String.valueOf(row.get("referenced_table")),
                                    new ArrayList<>()))
                    .columns().add(String.valueOf(row.get("column_name")));
        }
        return List.copyOf(byConstraint.values());
    }
}
