package io.github.databaseaudits.platform;

/**
 * The per-engine catalog SQL the catalog audits run. Each {@link DatabasePlatform}
 * holds one dialect ({@link DatabasePlatform#catalogDialect()}); the catalog audits
 * and {@link io.github.databaseaudits.catalog.IndexCatalog} ask the platform's dialect
 * for their SQL instead of switching on the platform.
 *
 * <p>
 * The three <em>abstract</em> methods return SQL that genuinely diverges between engines
 * — PostgreSQL's {@code pg_catalog}, MySQL/MariaDB's {@code information_schema.statistics}
 * and {@code key_column_usage}, H2's {@code information_schema}. A new engine's dialect
 * will not compile until it supplies all three, so the compiler enforces coverage the way
 * the old exhaustive {@code switch}es did. The <em>default</em> methods return the
 * standard {@code information_schema} SQL every supported engine shares; an engine with the
 * standard layout inherits them unchanged.
 *
 * <p>
 * To add an engine, add a {@link DatabasePlatform} constant with a {@code CatalogDialect}
 * — a new implementation for a divergent catalog, or an existing one (MariaDB reuses
 * {@link MysqlCatalogDialect}).
 */
public interface CatalogDialect {
    /**
     * Returns the SQL that reads every index of a schema with its key columns in index
     * order (one row per key column). Diverges per engine.
     *
     * @return the index-catalog SQL.
     */
    String indexCatalogSql();

    /**
     * Returns the SQL that reads every foreign key of a schema — its columns in
     * constraint order and its referenced table. Diverges per engine.
     *
     * @return the foreign-keys SQL.
     */
    String foreignKeysSql();

    /**
     * Returns the SQL that reads every foreign key column of a schema paired with its
     * declared type and its referenced column's declared type. Diverges per engine.
     *
     * @return the foreign-key-column-types SQL.
     */
    String foreignKeyColumnTypesSql();

    /**
     * Returns the SQL that lists every base table of a schema with no {@code PRIMARY KEY}.
     * Standard {@code information_schema}, valid as-is on PostgreSQL, MySQL, MariaDB, and H2.
     *
     * @return the tables-without-primary-key SQL.
     */
    default String tablesWithoutPrimaryKeySql() {
        return """
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
    }

    /**
     * Returns the SQL that reads every nullable foreign key column of a schema. Standard
     * {@code information_schema}, valid as-is on PostgreSQL, MySQL, MariaDB, and H2; the
     * join includes {@code table_name} because constraint names are only unique per table
     * on PostgreSQL and MySQL.
     *
     * @return the nullable-foreign-key-column SQL.
     */
    default String nullableForeignKeyColumnSql() {
        return """
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
    }

    /**
     * Returns the SQL that reads every primary key column of a schema with its
     * declared data type. Standard {@code information_schema}, valid as-is on
     * PostgreSQL, MySQL, MariaDB, and H2; the join includes {@code table_name}
     * because constraint names are only unique per table on PostgreSQL and MySQL.
     *
     * @return the primary-key-column-types SQL.
     */
    default String primaryKeyColumnTypesSql() {
        return """
                SELECT kcu.table_name  AS table_name,
                       kcu.column_name AS column_name,
                       col.data_type   AS data_type
                FROM   information_schema.table_constraints tc
                JOIN   information_schema.key_column_usage kcu
                  ON   kcu.constraint_schema = tc.constraint_schema
                 AND   kcu.constraint_name   = tc.constraint_name
                 AND   kcu.table_name        = tc.table_name
                JOIN   information_schema.columns col
                  ON   col.table_schema = kcu.table_schema
                 AND   col.table_name   = kcu.table_name
                 AND   col.column_name  = kcu.column_name
                WHERE  tc.constraint_type = 'PRIMARY KEY'
                  AND  tc.table_schema = ?
                ORDER  BY 1, 2
                """;
    }
}
