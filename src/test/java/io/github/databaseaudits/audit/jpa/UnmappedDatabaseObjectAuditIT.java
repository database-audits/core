package io.github.databaseaudits.audit.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.h2.jdbcx.JdbcDataSource;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;

import io.github.databaseaudits.audit.finding.Finding;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

/**
 * End-to-end validation of {@link UnmappedDatabaseObjectAudit} against a real
 * (in-memory H2) schema: {@link ParentEntity}/{@link ChildEntity} (plus
 * {@link ParentEntity}'s {@code @OneToMany @JoinTable} link) are validated
 * against schemas planted with each kind of unmapped object, and the audit
 * must report every one.
 */
class UnmappedDatabaseObjectAuditIT {
    private static final String CLEAN_PARENT =
            "CREATE TABLE parent (id BIGINT PRIMARY KEY, name VARCHAR(255), quantity INTEGER)";
    private static final String CLEAN_CHILD =
            "CREATE TABLE child (id BIGINT PRIMARY KEY, parent_id BIGINT)";
    private static final String CLEAN_JOIN_TABLE =
            "CREATE TABLE parent_children_link (parent_id BIGINT, child_id BIGINT)";

    private static final AtomicInteger DATABASE_COUNTER = new AtomicInteger();

    @Test
    void testAudit_SchemaExactlyMatchesEntitiesIncludingJoinTable_ReturnsNoViolations()
            throws SQLException {
        assertThat(auditAgainst(CLEAN_PARENT, CLEAN_CHILD, CLEAN_JOIN_TABLE))
                .as("A schema matching the entities - including the "
                        + "Hibernate-generated @JoinTable - should produce no violations.")
                .isEmpty();
    }

    @Test
    void testAudit_ExtraOrphanTable_ReportsTableFinding() throws SQLException {
        assertThat(auditAgainst(CLEAN_PARENT, CLEAN_CHILD, CLEAN_JOIN_TABLE,
                "CREATE TABLE orphan_table (id BIGINT)"))
                .as("A live table no entity maps should be reported.")
                .anySatisfy(violation -> assertThat(violation.description())
                        .contains("unmapped table")
                        .containsIgnoringCase("orphan_table"));
    }

    @Test
    void testAudit_ExtraNullableColumn_ReportsColumnFindingWithoutInsertBreakingSuffix()
            throws SQLException {
        assertThat(auditAgainst(
                "CREATE TABLE parent (id BIGINT PRIMARY KEY, name VARCHAR(255), "
                        + "quantity INTEGER, legacy_note VARCHAR(255))",
                CLEAN_CHILD, CLEAN_JOIN_TABLE))
                .as("A nullable column no entity maps should be reported, without the insert-breaking suffix.")
                .anySatisfy(violation -> assertThat(violation.description())
                        .contains("unmapped column")
                        .containsIgnoringCase("legacy_note")
                        .containsIgnoringCase("parent")
                        .doesNotContain("breaks entity inserts"));
    }

    @Test
    void testAudit_ExtraNotNullColumnWithoutDefault_ReportsColumnFindingWithInsertBreakingSuffix()
            throws SQLException {
        assertThat(auditAgainst(
                "CREATE TABLE parent (id BIGINT PRIMARY KEY, name VARCHAR(255), "
                        + "quantity INTEGER, tenant_id VARCHAR(10) NOT NULL)",
                CLEAN_CHILD, CLEAN_JOIN_TABLE))
                .as("A NOT NULL column with no default that no entity maps breaks entity inserts, and must say so.")
                .anySatisfy(violation -> assertThat(violation.description())
                        .contains("unmapped column")
                        .containsIgnoringCase("tenant_id")
                        .contains("breaks entity inserts"));
    }

    @Test
    void testAudit_ExcludedRelations_AreReportedThenSuppressed()
            throws SQLException {
        final String parentWithLegacyNote =
                "CREATE TABLE parent (id BIGINT PRIMARY KEY, name VARCHAR(255), "
                        + "quantity INTEGER, legacy_note VARCHAR(255))";

        assertThat(auditAgainst(parentWithLegacyNote, CLEAN_CHILD,
                CLEAN_JOIN_TABLE, "CREATE TABLE orphan_table (id BIGINT)"))
                .as("Without exclusions, both the orphan table and the unmapped column are reported.")
                .anySatisfy(violation -> assertThat(violation.description())
                        .containsIgnoringCase("orphan_table"))
                .anySatisfy(violation -> assertThat(violation.description())
                        .containsIgnoringCase("legacy_note"));

        assertThat(auditAgainst(Set.of("orphan_table", "parent.legacy_note"),
                parentWithLegacyNote, CLEAN_CHILD, CLEAN_JOIN_TABLE,
                "CREATE TABLE orphan_table (id BIGINT)"))
                .as("Excluding the orphan table and the unmapped column suppresses both findings.")
                .isEmpty();
    }

    @Test
    void testAudit_SchemaQualifiedExclusions_AreSuppressed() throws SQLException {
        final String parentWithLegacyNote =
                "CREATE TABLE parent (id BIGINT PRIMARY KEY, name VARCHAR(255), "
                        + "quantity INTEGER, legacy_note VARCHAR(255))";

        assertThat(auditAgainst(
                Set.of("public.orphan_table", "public.parent.legacy_note"),
                parentWithLegacyNote, CLEAN_CHILD, CLEAN_JOIN_TABLE,
                "CREATE TABLE orphan_table (id BIGINT)"))
                .as("Schema-qualified exclusions - schema.table and "
                        + "schema.table.column - should suppress the matching "
                        + "findings (H2's default schema is PUBLIC).")
                .isEmpty();
    }

    private static List<Finding> auditAgainst(final String... ddl)
            throws SQLException {
        return auditAgainst(Set.of(), ddl);
    }

    private static List<Finding> auditAgainst(
            final Set<String> excludedRelations, final String... ddl)
            throws SQLException {
        final String url = "jdbc:h2:mem:unmapped_object_audit_"
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
            return new UnmappedDatabaseObjectAudit(() -> metadata, dataSource)
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
        @OneToMany
        @JoinTable(name = "parent_children_link",
                joinColumns = @JoinColumn(name = "parent_id"),
                inverseJoinColumns = @JoinColumn(name = "child_id"))
        private List<ChildEntity> linkedChildren;

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
