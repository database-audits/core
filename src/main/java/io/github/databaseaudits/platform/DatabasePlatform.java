package io.github.databaseaudits.platform;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

import javax.sql.DataSource;

import io.github.databaseaudits.audit.jpa.SchemaEntityValidationAudit;
import io.github.databaseaudits.audit.runtime.UnconditionalMutationAudit;

/**
 * The database product an audit runs against — selects the catalog SQL the
 * audit executes.
 *
 * <p>
 * Every catalog-driven audit supports every platform listed here. The
 * EXPLAIN-driven (plan) runtime audits require {@link #POSTGRESQL} — no other
 * platform offers a parameter-free generic-plan EXPLAIN with planner-penalty
 * settings — and fail fast (rather than pass vacuously) on any other platform.
 * {@link UnconditionalMutationAudit} and {@link SchemaEntityValidationAudit}
 * run no database-specific SQL and need no platform.
 *
 * <p>
 * Pass the platform to each audit's constructor, or detect it once with
 * {@link #fromDataSource(DataSource)} (the Spring module's
 * {@code DatabaseAuditTestConfiguration} does exactly that).
 *
 * <p>
 * To add a platform, add an enum value: every per-audit SQL {@code switch} is
 * exhaustive with no {@code default}, so the compiler then flags each place
 * that needs SQL for the new platform.
 */
public enum DatabasePlatform {
    /** H2 2.x (the 1.x information_schema had a different layout). */
    H2,

    /**
     * MariaDB 10.6+. (Connecting to MariaDB through MySQL Connector/J detects
     * as {@link #MYSQL} — same SQL.)
     */
    MARIADB,

    /** MySQL 8+. Aurora MySQL reports as MySQL. */
    MYSQL,

    /**
     * PostgreSQL 11+ for the catalog audits, 16+ for the plan audits. Aurora
     * PostgreSQL reports as PostgreSQL.
     */
    POSTGRESQL;

    private static final String FAILED_OBTAINING_DB_FROM_DATA_SOURCE_MSG =
            "Could not read the database product name from the DataSource";

    /**
     * Detects the platform from
     * {@link java.sql.DatabaseMetaData#getDatabaseProductName()}, opening (and
     * closing) one connection.
     *
     * @param dataSource
     *                       The data source to inspect.
     * @throws IllegalStateException
     *                                      If a connection or its metadata
     *                                      cannot be obtained.
     * @throws IllegalArgumentException
     *                                      If the product is not a supported
     *                                      platform.
     */
    public static DatabasePlatform fromDataSource(final DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            return fromProductName(
                    connection.getMetaData().getDatabaseProductName());
        } catch (final SQLException e) {
            throw new IllegalStateException(
                    FAILED_OBTAINING_DB_FROM_DATA_SOURCE_MSG, e);
        }
    }

    /**
     * Maps a JDBC database product name (case-insensitive:
     * {@code "PostgreSQL"}, {@code "MySQL"}, {@code "MariaDB"}, {@code "H2"})
     * to a platform.
     *
     * @param productName
     *                        The JDBC database product name.
     * @throws IllegalArgumentException
     *                                      If the product is not a supported
     *                                      platform.
     */
    public static DatabasePlatform fromProductName(final String productName) {
        final DatabasePlatform databasePlatform;

        final String name =
                productName == null ? "" : productName.toLowerCase(Locale.ROOT);
        if (name.contains("h2")) {
            databasePlatform = H2;
        } else if (name.contains("mariadb")) {
            databasePlatform = MARIADB;
        } else if (name.contains("mysql")) {
            databasePlatform = MYSQL;
        } else if (name.contains("postgres")) {
            databasePlatform = POSTGRESQL;
        } else {
            throw new IllegalArgumentException(
                    "Unsupported database platform '%s' — supported: PostgreSQL, MySQL, MariaDB, H2"
                            .formatted(productName));
        }

        return databasePlatform;
    }
}
