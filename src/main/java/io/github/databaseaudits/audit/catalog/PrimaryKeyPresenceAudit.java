package io.github.databaseaudits.audit.catalog;

import java.util.List;
import java.util.Set;

import io.github.databaseaudits.audit.finding.Finding;
import io.github.databaseaudits.audit.finding.MissingPrimaryKeyFinding;
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

    private final CatalogQueries catalogQueries;
    private final DatabasePlatform platform;

    String sql() {
        return platform.catalogDialect().tablesWithoutPrimaryKeySql();
    }

    /**
     * Returns one {@link Finding} for every base table with no
     * {@code PRIMARY KEY}, except the excluded ones; an empty list when every
     * table has one.
     *
     * @param schema
     *                           The schema to scan.
     * @param excludedTables
     *                           The table names to skip, matched
     *                           case-insensitively (MySQL and MariaDB report
     *                           unquoted table names in upper case).
     * @return One {@link Finding} per base table with no {@code PRIMARY KEY} —
     *         its {@link Finding#description() description} is the table name;
     *         an empty list when every table has one.
     */
    public List<Finding> audit(final String schema,
            final Set<String> excludedTables) {
        return catalogQueries.queryForList(sql(), schema).stream()
                .map(r -> String.valueOf(r.get("table_name")))
                .filter(t -> !excludedTables.contains(t))
                .<Finding>map(MissingPrimaryKeyFinding::new).toList();
    }
}
