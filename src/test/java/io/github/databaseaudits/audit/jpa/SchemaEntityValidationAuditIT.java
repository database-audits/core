package io.github.databaseaudits.audit.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.h2.jdbcx.JdbcDataSource;
import org.hibernate.SessionFactory;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * End-to-end validation of {@link SchemaEntityValidationAudit} against a real
 * (in-memory H2) schema: the entities {@link ParentEntity}/{@link ChildEntity}
 * are validated against schemas planted with each kind of drift, and the audit
 * must report every one — including several at once in a single run, the
 * behavior {@code ddl-auto=validate} cannot provide.
 */
class SchemaEntityValidationAuditIT {
    private static final String CLEAN_PARENT =
            "CREATE TABLE parent (id BIGINT PRIMARY KEY, name VARCHAR(255), quantity INTEGER)";
    private static final String CLEAN_CHILD =
            "CREATE TABLE child (id BIGINT PRIMARY KEY, parent_id BIGINT)";

    private static final AtomicInteger DATABASE_COUNTER = new AtomicInteger();

    @Test
    void testAudit_SchemaMatchesEntities_ReturnsNoViolations()
            throws SQLException {
        assertThat(auditAgainst(CLEAN_PARENT, CLEAN_CHILD))
                .as("Matching schema should produce no violations.")
                .isEmpty();
    }

    @Test
    void testAudit_MissingTable_ReportsMissingTable() throws SQLException {
        assertThat(auditAgainst(CLEAN_PARENT))
                .as("Entity with no table should be reported as a missing table.")
                .anySatisfy(violation -> assertThat(violation)
                        .as("A violation should name the missing child table.")
                        .contains("missing table").contains("child"));
    }

    @Test
    void testAudit_MissingColumn_ReportsMissingColumn() throws SQLException {
        assertThat(auditAgainst(
                "CREATE TABLE parent (id BIGINT PRIMARY KEY, name VARCHAR(255))",
                CLEAN_CHILD))
                .as("Mapped column absent from the table should be reported.")
                .anySatisfy(violation -> assertThat(violation)
                        .as("A violation should name the missing parent.quantity column.")
                        .contains("missing column").contains("quantity")
                        .contains("parent"));
    }

    @Test
    void testAudit_WrongColumnType_ReportsTypeMismatch() throws SQLException {
        assertThat(auditAgainst(
                "CREATE TABLE parent (id BIGINT PRIMARY KEY, name INTEGER, quantity INTEGER)",
                CLEAN_CHILD))
                .as("Column whose type differs from the mapping should be reported.")
                .anySatisfy(violation -> assertThat(violation)
                        .as("A violation should name parent.name's wrong type.")
                        .contains("wrong column type").contains("name")
                        .contains("varchar"));
    }

    @Test
    void testAudit_MultipleDriftsInOneSchema_ReportsThemAllAtOnce()
            throws SQLException {
        final List<String> violations = auditAgainst(
                "CREATE TABLE parent (id BIGINT PRIMARY KEY, name INTEGER)");

        assertThat(violations)
                .as("Every mismatch should surface in a single run: child's "
                        + "missing table, parent's missing quantity column, and "
                        + "parent.name's wrong type.")
                .hasSize(3)
                .anySatisfy(violation -> assertThat(violation)
                        .as("The missing child table should be among the violations.")
                        .contains("missing table").contains("child"))
                .anySatisfy(violation -> assertThat(violation)
                        .as("The missing parent.quantity column should be among the violations.")
                        .contains("missing column").contains("quantity"))
                .anySatisfy(violation -> assertThat(violation)
                        .as("The parent.name type mismatch should be among the violations.")
                        .contains("wrong column type").contains("name"));
    }

    @Test
    void testAudit_ExcludedRelations_AreReportedThenSuppressed()
            throws SQLException {
        final String parentWithWrongName =
                "CREATE TABLE parent (id BIGINT PRIMARY KEY, name INTEGER, quantity INTEGER)";

        assertThat(auditAgainst(parentWithWrongName))
                .as("Without exclusions, the missing child table and the "
                        + "parent.name type mismatch are both reported.")
                .anySatisfy(violation -> assertThat(violation)
                        .as("The missing child table is reported.")
                        .contains("missing table").contains("child"))
                .anySatisfy(violation -> assertThat(violation)
                        .as("The parent.name type mismatch is reported.")
                        .contains("wrong column type").contains("name"));

        assertThat(auditAgainst(Set.of("child", "parent.name"),
                parentWithWrongName))
                .as("Excluding the child table and the parent.name column "
                        + "suppresses both findings.")
                .isEmpty();
    }

    @Test
    void testAudit_SchemaQualifiedExclusions_AreSuppressed()
            throws SQLException {
        assertThat(auditAgainst(Set.of("public.child", "public.parent.name"),
                "CREATE TABLE parent (id BIGINT PRIMARY KEY, name INTEGER, quantity INTEGER)"))
                .as("Schema-qualified exclusions — schema.table and "
                        + "schema.table.column — should suppress the matching "
                        + "findings (H2's default schema is PUBLIC).")
                .isEmpty();
    }

    @Test
    void testForEntityManagerFactory_RealSessionFactory_CapturesMetadataAndValidates()
            throws SQLException {
        final String url = "jdbc:h2:mem:jpa_audit_sf_"
                + DATABASE_COUNTER.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        final JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(url);
        dataSource.setUser("sa");
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(CLEAN_PARENT);
            statement.execute(CLEAN_CHILD);
        }

        final StandardServiceRegistry registry =
                new StandardServiceRegistryBuilder()
                        .applySetting("hibernate.connection.url", url)
                        .applySetting("hibernate.connection.username", "sa")
                        .applySetting("hibernate.hbm2ddl.auto", "none").build();
        final SessionFactory sessionFactory = new MetadataSources(registry)
                .addAnnotatedClass(ParentEntity.class)
                .addAnnotatedClass(ChildEntity.class).buildMetadata()
                .buildSessionFactory();
        try {
            assertThat(MappingMetadataIntegrator.metadataFor(sessionFactory))
                    .as("The ServiceLoader-registered integrator should capture "
                            + "the boot metadata when the SessionFactory is built.")
                    .isNotNull();
            assertThat(SchemaEntityValidationAudit
                    .forEntityManagerFactory(sessionFactory, dataSource).audit())
                    .as("forEntityManagerFactory should resolve the captured "
                            + "metadata and find the matching schema clean.")
                    .isEmpty();
        } finally {
            sessionFactory.close();
        }

        assertThat(MappingMetadataIntegrator.metadataFor(sessionFactory))
                .as("Closing the SessionFactory should drop the captured metadata.")
                .isNull();
    }

    private static List<String> auditAgainst(final String... ddl)
            throws SQLException {
        return auditAgainst(Set.of(), ddl);
    }

    private static List<String> auditAgainst(final Set<String> excludedRelations,
            final String... ddl) throws SQLException {
        final String url = "jdbc:h2:mem:jpa_audit_"
                + DATABASE_COUNTER.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        final JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(url);
        dataSource.setUser("sa");
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            for (final String statementSql : ddl) {
                statement.execute(statementSql);
            }
        }

        final StandardServiceRegistry registry =
                new StandardServiceRegistryBuilder()
                        .applySetting("hibernate.connection.url", url)
                        .applySetting("hibernate.connection.username", "sa")
                        .applySetting("hibernate.hbm2ddl.auto", "none")
                        .build();
        try {
            final Metadata metadata = new MetadataSources(registry)
                    .addAnnotatedClass(ParentEntity.class)
                    .addAnnotatedClass(ChildEntity.class).buildMetadata();
            return new SchemaEntityValidationAudit(() -> metadata, dataSource)
                    .audit(excludedRelations);
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    @Entity
    @Table(name = "parent")
    static class ParentEntity {
        @Id
        private Long id;
        @Column(name = "name")
        private String name;
        @Column(name = "quantity")
        private int quantity;

        ParentEntity() {
        }
    }

    @Entity
    @Table(name = "child")
    static class ChildEntity {
        @Id
        private Long id;
        @Column(name = "parent_id")
        private Long parentId;

        ChildEntity() {
        }
    }
}
