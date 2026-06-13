package io.github.databaseaudits.audit.catalog;

import java.util.List;
import java.util.Set;

import io.github.databaseaudits.jdbc.CatalogQueries;
import io.github.databaseaudits.platform.DatabasePlatform;
import lombok.AllArgsConstructor;

/**
 * Every application table must have a PRIMARY KEY.
 *
 * <p>
 * A table without a primary key is almost always a mistake in a JPA
 * application: rows cannot be reliably addressed, {@code UPDATE}/{@code DELETE}
 * by identity is impossible, and many tools misbehave. Pass the tables to
 * ignore as {@code excludedTables} — {@link #LIQUIBASE_BOOKKEEPING_TABLES} is
 * provided for the common case. Catalog-driven, deterministic; supports every
 * {@link DatabasePlatform}.
 *
 * <p>
 * Fix: add a {@code PRIMARY KEY} to each table, or exclude it (e.g. Liquibase
 * bookkeeping tables).
 */
@AllArgsConstructor
public class PrimaryKeyPresenceAudit {
    /**
     * Liquibase bookkeeping tables — never part of the application's data
     * model.
     */
    public static final Set<String> LIQUIBASE_BOOKKEEPING_TABLES =
            Set.of("databasechangelog", "databasechangeloglock");

    private final CatalogQueries jdbcSupport;
    private final DatabasePlatform platform;

    /**
     * Standard information_schema, valid as-is on PostgreSQL, MySQL, MariaDB,
     * and H2.
     */
    private static final String INFORMATION_SCHEMA_TABLES_WITHOUT_PK_SQL = """
            SELECT t.table_name
            FROM   information_schema.tables t
            WHERE  t.table_schema = ?
              AND  t.table_type = 'BASE TABLE'
              AND  NOT EXISTS (
                     SELECT 1
                     FROM   information_schema.table_constraints tc
                     WHERE  tc.table_schema    = t.table_schema
                       AND  tc.table_name      = t.table_name
                       AND  tc.constraint_type = 'PRIMARY KEY'
                   )
            ORDER BY t.table_name
            """;

    String sql() {
        return switch (platform) {
        case POSTGRESQL, MYSQL, MARIADB, H2 ->
            INFORMATION_SCHEMA_TABLES_WITHOUT_PK_SQL;
        };
    }

    /**
     * Returns the name of every base table with no {@code PRIMARY KEY}, except
     * the excluded ones; an empty list when every table has one.
     *
     * @param schema
     *                           The schema to scan.
     * @param excludedTables
     *                           The table names to skip.
     */
    public List<String> audit(final String schema,
            final Set<String> excludedTables) {
        return jdbcSupport.queryForList(sql(), schema).stream()
                .map(r -> String.valueOf(r.get("table_name")))
                .filter(t -> !excludedTables.contains(t)).toList();
    }
}
