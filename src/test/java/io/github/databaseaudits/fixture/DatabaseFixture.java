package io.github.databaseaudits.fixture;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;

import io.github.databaseaudits.platform.DatabasePlatform;

/**
 * One supported database provisioned for the integration tests, with the
 * violation schema the catalog audits are verified against. The per-platform
 * variation — DataSource, schema name, identifier case, violation planting —
 * lives here as data and per-constant overrides, mirroring the exhaustive-enum
 * approach of {@link DatabasePlatform}, so the test classes stay generic over
 * the database.
 *
 * <p>
 * {@link #createViolationSchema()} plants every auditable catalog violation: a
 * table with no primary key, a nullable foreign key column, a leading-prefix
 * redundant index pair, a foreign key column whose declared type differs from
 * its referenced column's, and (where the platform permits one to exist) a
 * foreign key with no supporting index. The container-backed fixtures require
 * Docker and fail rather than skip without it (see {@link DatabaseContainers}).
 */
public enum DatabaseFixture {
    /**
     * Embedded in-memory H2. Reports unquoted identifiers in UPPER CASE;
     * auto-creates an index for every FK.
     */
    H2(DatabasePlatform.H2) {
        @Override
        public DataSource dataSource() {
            final var dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:catalog_audits;DB_CLOSE_DELAY=-1");
            return dataSource;
        }

        @Override
        public String schema() {
            return "PUBLIC";
        }

        @Override
        public String expectedIdentifier(final String identifier) {
            return identifier.toUpperCase(Locale.ROOT);
        }

        @Override
        public boolean fkIndexViolationPlanted() {
            return false;
        }

        @Override
        void plantPlatformSpecifics(final Statement statement)
                throws SQLException {
            // H2 permits an INTEGER FK referencing the BIGINT parent(id) — the
            // planted type mismatch
            // (H2 auto-creates the FK index)
            statement.execute(
                    """
                            CREATE TABLE type_mismatch_child (
                                id         BIGINT PRIMARY KEY,
                                parent_ref INTEGER NOT NULL,
                                CONSTRAINT fk_type_mismatch_parent FOREIGN KEY (parent_ref) REFERENCES parent(id)
                            )""");
        }
    },
    /**
     * PostgreSQL container. The only platform that never auto-indexes foreign
     * keys.
     */
    POSTGRESQL(DatabasePlatform.POSTGRESQL) {
        @Override
        public DataSource dataSource() {
            return DatabaseContainers.postgreSqlDataSource();
        }

        @Override
        public String schema() {
            return "audit_violations";
        }

        @Override
        void beforeCommonSchema(final Statement statement) throws SQLException {
            // a dedicated schema keeps these tables isolated from
            // PlanAuditsPostgreSqlIT's tables in public
            statement.execute("CREATE SCHEMA audit_violations");
            statement.execute("SET search_path TO audit_violations");
        }

        @Override
        void plantPlatformSpecifics(final Statement statement)
                throws SQLException {
            // PostgreSQL never auto-indexes FKs: index child's FKs explicitly
            // so only the planted FK is unindexed
            statement.execute(
                    "CREATE INDEX idx_child_parent   ON child(parent_id)");
            statement.execute(
                    "CREATE INDEX idx_child_optional ON child(optional_parent_id)");
            // PostgreSQL permits an integer FK referencing the bigint
            // parent(id) — the planted type mismatch
            // (indexed explicitly so the FK-index audit keeps reporting only
            // its own planted violation)
            statement.execute(
                    """
                            CREATE TABLE type_mismatch_child (
                                id         BIGINT PRIMARY KEY,
                                parent_ref INTEGER NOT NULL,
                                CONSTRAINT fk_type_mismatch_parent FOREIGN KEY (parent_ref) REFERENCES parent(id)
                            )""");
            statement.execute(
                    "CREATE INDEX idx_type_mismatch_ref ON type_mismatch_child(parent_ref)");
        }
    },
    /**
     * MySQL container. InnoDB auto-indexes FKs, and MySQL refuses to drop a
     * constraint-required index (error 1553) even with FOREIGN_KEY_CHECKS=0, so
     * the unindexed-FK violation cannot exist on MySQL.
     */
    MYSQL(DatabasePlatform.MYSQL) {
        @Override
        public DataSource dataSource() {
            return DatabaseContainers.mySqlDataSource();
        }

        @Override
        public String schema() {
            return DatabaseContainers.mySqlDatabaseName();
        }

        @Override
        public boolean fkIndexViolationPlanted() {
            return false;
        }

        @Override
        public String mismatchedFkColumn() {
            return "code_ref";
        }

        @Override
        void plantPlatformSpecifics(final Statement statement)
                throws SQLException {
            plantVarcharLengthMismatchedFk(statement);
        }
    },
    /**
     * MariaDB container. Auto-indexes FKs like MySQL, but permits dropping the
     * index once checks are suspended.
     */
    MARIADB(DatabasePlatform.MARIADB) {
        @Override
        public DataSource dataSource() {
            return DatabaseContainers.mariaDbDataSource();
        }

        @Override
        public String schema() {
            return DatabaseContainers.mariaDbDatabaseName();
        }

        @Override
        public String mismatchedFkColumn() {
            return "code_ref";
        }

        @Override
        void plantPlatformSpecifics(final Statement statement)
                throws SQLException {
            // InnoDB auto-created an index (named after the constraint) for the
            // FK; unlike MySQL, MariaDB permits
            // dropping it while the foreign-key checks are suspended — the
            // "index dropped after the fact" scenario
            // ForeignKeyIndexAudit documents
            statement.execute("SET FOREIGN_KEY_CHECKS = 0");
            statement.execute(
                    "ALTER TABLE unindexed_fk_child DROP INDEX fk_unindexed_child_parent");
            statement.execute("SET FOREIGN_KEY_CHECKS = 1");
            plantVarcharLengthMismatchedFk(statement);
        }
    };

    private final DatabasePlatform platform;

    DatabaseFixture(final DatabasePlatform platform) {
        this.platform = platform;
    }

    public DatabasePlatform platform() {
        return platform;
    }

    /**
     * A fresh (non-pooling) DataSource to this fixture's database;
     * container-backed fixtures require Docker.
     */
    public abstract DataSource dataSource();

    /**
     * The schema the audits scan — where {@link #createViolationSchema()}
     * creates its tables.
     */
    public abstract String schema();

    /**
     * How this platform's catalog reports the unquoted lower_snake_case
     * identifiers the DDL uses.
     */
    public String expectedIdentifier(final String identifier) {
        return identifier;
    }

    /**
     * Whether {@code fk_unindexed_child_parent} ends up without a supporting
     * index. False on H2 (auto-creates an FK index that cannot be dropped) and
     * MySQL (auto-creates one and refuses the drop even with FOREIGN_KEY_CHECKS
     * suspended) — on those platforms the violation cannot exist, and the
     * audit's pass is the platform's guarantee.
     */
    public boolean fkIndexViolationPlanted() {
        return true;
    }

    /**
     * The column of {@code type_mismatch_child} planted with a declared type
     * differing from its referenced column's: {@code parent_ref INTEGER}
     * referencing the BIGINT {@code parent(id)}, except on MySQL/MariaDB, which
     * reject integer-width FK mismatches but permit differing VARCHAR lengths
     * ({@code code_ref}).
     */
    public String mismatchedFkColumn() {
        return "parent_ref";
    }

    /**
     * Platform setup before the common DDL runs (same connection), e.g.
     * creating and selecting the schema.
     */
    void beforeCommonSchema(final Statement statement) throws SQLException {
    }

    /**
     * Platform-specific violation planting after the common DDL (same
     * connection).
     */
    void plantPlatformSpecifics(final Statement statement) throws SQLException {
    }

    /**
     * Creates the violation schema: common DDL valid on all four platforms,
     * bracketed by the per-platform hooks. Run once per database by
     * {@code CatalogAuditsIT}'s parameterized-class lifecycle.
     */
    public final void createViolationSchema() throws SQLException {
        try (Connection connection = dataSource().getConnection();
                Statement statement = connection.createStatement()) {
            beforeCommonSchema(statement);
            statement.execute("""
                    CREATE TABLE parent (
                        id   BIGINT PRIMARY KEY,
                        code VARCHAR(10) NOT NULL
                    )""");
            statement.execute(
                    "CREATE UNIQUE INDEX uq_parent_code ON parent(code)");
            // optional_parent_id is the planted nullable-FK violation
            statement.execute(
                    """
                            CREATE TABLE child (
                                id                 BIGINT PRIMARY KEY,
                                parent_id          BIGINT NOT NULL,
                                optional_parent_id BIGINT,
                                created_at         TIMESTAMP,
                                CONSTRAINT fk_child_parent   FOREIGN KEY (parent_id)          REFERENCES parent(id),
                                CONSTRAINT fk_child_optional FOREIGN KEY (optional_parent_id) REFERENCES parent(id)
                            )""");
            // idx_child_created is a leading prefix of idx_child_created_id ->
            // the planted redundancy
            statement.execute(
                    "CREATE INDEX idx_child_created    ON child(created_at)");
            statement.execute(
                    "CREATE INDEX idx_child_created_id ON child(created_at, id)");
            statement.execute("CREATE TABLE no_pk_table (data VARCHAR(10))");
            statement.execute(
                    """
                            CREATE TABLE unindexed_fk_child (
                                id        BIGINT PRIMARY KEY,
                                parent_id BIGINT NOT NULL,
                                CONSTRAINT fk_unindexed_child_parent FOREIGN KEY (parent_id) REFERENCES parent(id)
                            )""");
            plantPlatformSpecifics(statement);
        }
    }

    /**
     * MySQL/MariaDB reject integer-width FK type mismatches outright, but
     * permit differing VARCHAR lengths — the plantable type mismatch there:
     * {@code code_ref VARCHAR(20)} referencing {@code parent.code VARCHAR(10)}
     * (InnoDB auto-creates the FK index, so the FK-index audit stays
     * unaffected).
     */
    private static void plantVarcharLengthMismatchedFk(
            final Statement statement) throws SQLException {
        statement.execute(
                """
                        CREATE TABLE type_mismatch_child (
                            id       BIGINT PRIMARY KEY,
                            code_ref VARCHAR(20) NOT NULL,
                            CONSTRAINT fk_type_mismatch_parent FOREIGN KEY (code_ref) REFERENCES parent(code)
                        )""");
    }
}
