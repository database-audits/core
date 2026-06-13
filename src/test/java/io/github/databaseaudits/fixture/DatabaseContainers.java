package io.github.databaseaudits.fixture;

import java.sql.SQLException;

import javax.sql.DataSource;

import org.mariadb.jdbc.MariaDbDataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.jdbc.PreferQueryMode;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.mysql.cj.jdbc.MysqlDataSource;

import io.github.databaseaudits.platform.DatabasePlatform;

/**
 * The real-database containers shared by the integration tests — the
 * Testcontainers <em>singleton</em> pattern: each engine starts at most once
 * per JVM (lazily on first use, so a single-platform run starts only what it
 * needs) and is shared by every integration test in the failsafe run; the
 * Testcontainers reaper removes the containers after the JVM exits.
 *
 * <p>
 * The default image tags pin the documented platform floors ({@code postgres}
 * 16 is the plan-audit minimum; {@code mysql} 8.4 because 8.0 is EOL;
 * {@code mariadb} 10.6) — bump them together with the support claims in
 * {@link DatabasePlatform}'s javadoc. Each can be overridden per run for ad-hoc
 * engine testing, e.g.
 * {@code .\mvnw.cmd -pl core verify "-Ddatabaseaudits.it.postgresql.image=postgres:17-alpine"}.
 *
 * <p>
 * Docker must be running (locally: start "Rancher Desktop"). Without it these
 * accessors fail with a clear error — the database integration tests
 * deliberately fail rather than skip, so the supported-platform matrix is never
 * silently unverified.
 */
public class DatabaseContainers {
    private static final String MARIADB_IMAGE = System
            .getProperty("databaseaudits.it.mariadb.image", "mariadb:10.6");
    private static final String MYSQL_IMAGE =
            System.getProperty("databaseaudits.it.mysql.image", "mysql:8.4");
    private static final String POSTGRESQL_IMAGE = System.getProperty(
            "databaseaudits.it.postgresql.image", "postgres:16-alpine");

    private static final class PostgreSql {
        static final PostgreSQLContainer CONTAINER =
                started(new PostgreSQLContainer(POSTGRESQL_IMAGE));
    }

    private static final class MySql {
        static final MySQLContainer CONTAINER =
                started(new MySQLContainer(MYSQL_IMAGE));
    }

    private static final class MariaDb {
        static final MariaDBContainer CONTAINER =
                started(new MariaDBContainer(MARIADB_IMAGE));
    }

    private DatabaseContainers() {
    }

    /** DataSource (PostgreSQL driver) to the shared PostgreSQL container. */
    public static DataSource postgreSqlDataSource() {
        final var dataSource = new PGSimpleDataSource();
        dataSource.setUrl(PostgreSql.CONTAINER.getJdbcUrl());
        dataSource.setUser(PostgreSql.CONTAINER.getUsername());
        dataSource.setPassword(PostgreSql.CONTAINER.getPassword());
        // generic-plan EXPLAIN of statements containing $n placeholders needs
        // the simple query protocol; the
        // default extended protocol fails their Bind (see QueryPlanExplainer)
        dataSource.setPreferQueryMode(PreferQueryMode.SIMPLE);
        return dataSource;
    }

    /** DataSource (MySQL Connector/J) to the shared MySQL container. */
    public static DataSource mySqlDataSource() {
        final var dataSource = new MysqlDataSource();
        dataSource.setUrl(MySql.CONTAINER.getJdbcUrl());
        dataSource.setUser(MySql.CONTAINER.getUsername());
        dataSource.setPassword(MySql.CONTAINER.getPassword());
        return dataSource;
    }

    /**
     * The MySQL container's database, which is the schema the catalog audits
     * scan on MySQL.
     */
    public static String mySqlDatabaseName() {
        return MySql.CONTAINER.getDatabaseName();
    }

    /**
     * DataSource (MariaDB Connector/J, so platform detection sees MariaDB) to
     * the shared MariaDB container.
     */
    public static DataSource mariaDbDataSource() {
        try {
            final var dataSource =
                    new MariaDbDataSource(MariaDb.CONTAINER.getJdbcUrl());
            dataSource.setUser(MariaDb.CONTAINER.getUsername());
            dataSource.setPassword(MariaDb.CONTAINER.getPassword());
            return dataSource;
        } catch (final SQLException e) {
            throw new IllegalStateException(
                    "Could not configure the MariaDB DataSource", e);
        }
    }

    /**
     * The MariaDB container's database, which is the schema the catalog audits
     * scan on MariaDB.
     */
    public static String mariaDbDatabaseName() {
        return MariaDb.CONTAINER.getDatabaseName();
    }

    private static <C extends JdbcDatabaseContainer<C>> C started(
            final C container) {
        try {
            container.start();
            return container;
        } catch (final RuntimeException e) {
            throw new IllegalStateException(
                    ("""
                            Could not start the %s container. Docker must be running (locally: start "Rancher Desktop"). \
                            The database integration tests deliberately fail rather than skip without Docker, so the \
                            supported-platform matrix is never silently unverified.""")
                            .formatted(container.getDockerImageName()),
                    e);
        }
    }
}
