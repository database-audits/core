package io.github.databaseaudits.audit.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.hibernate.annotations.Immutable;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;

import io.github.databaseaudits.audit.finding.Finding;

import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * End-to-end validation of {@link MissingVersionAttributeAudit} against a
 * real Hibernate boot mapping model built from nested entity classes covering
 * each scenario the audit distinguishes. Only the mapping model is built — no
 * DDL is ever run.
 */
class MissingVersionAttributeAuditIT {

    @Test
    void testAudit_VersionedEntity_NotReported() {
        assertThat(auditAgainst(VersionedEntity.class))
                .as("An entity with a @Version attribute should not be reported.")
                .isEmpty();
    }

    @Test
    void testAudit_UnversionedEntity_ReportsEntityAndTable() {
        assertThat(auditAgainst(UnversionedEntity.class))
                .extracting(Finding::description)
                .as("An entity with no @Version attribute should be reported, naming the entity and its table.")
                .anySatisfy(description -> assertThat(description)
                        .contains(UnversionedEntity.class.getName())
                        .contains("unversioned"));
    }

    @Test
    void testAudit_ImmutableEntityWithoutVersion_NotReported() {
        assertThat(auditAgainst(ImmutableEntity.class))
                .as("An @Immutable entity cannot lose an update, so it should not be reported even unversioned.")
                .isEmpty();
    }

    @Test
    void testAudit_SingleTableSubclass_NotDoubleReported() {
        final List<Finding> violations =
                auditAgainst(RootEntity.class, SubEntity.class);

        assertThat(violations)
                .as("Versioning is declared on the hierarchy root, so only the root should be reported, once.")
                .extracting(Finding::description)
                .hasSize(1)
                .anySatisfy(description -> assertThat(description)
                        .contains(RootEntity.class.getName()));
    }

    @Test
    void testAudit_ExcludedBySimpleName_Suppressed() {
        assertThat(auditAgainst(Set.of("UnversionedEntity"),
                UnversionedEntity.class))
                .as("Excluding by the entity's simple name should suppress the finding.")
                .isEmpty();
    }

    @Test
    void testAudit_ExcludedByFullyQualifiedName_Suppressed() {
        assertThat(auditAgainst(Set.of(UnversionedEntity.class.getName()),
                UnversionedEntity.class))
                .as("Excluding by the entity's fully-qualified name should suppress the finding.")
                .isEmpty();
    }

    @Test
    void testAudit_ExcludedByTableName_Suppressed() {
        assertThat(auditAgainst(Set.of("unversioned"), UnversionedEntity.class))
                .as("Excluding by the mapped table name should suppress the finding.")
                .isEmpty();
    }

    private static List<Finding> auditAgainst(
            final Class<?>... entityClasses) {
        return auditAgainst(Set.of(), entityClasses);
    }

    private static List<Finding> auditAgainst(
            final Set<String> excludedEntities,
            final Class<?>... entityClasses) {
        // no live connection needed - only the boot mapping model is built,
        // never any DDL, but Hibernate still needs a dialect to resolve
        // column types
        final StandardServiceRegistry registry =
                new StandardServiceRegistryBuilder()
                        .applySetting("hibernate.dialect",
                                "org.hibernate.dialect.H2Dialect")
                        .build();
        try {
            final MetadataSources sources = new MetadataSources(registry);
            for (final Class<?> entityClass : entityClasses) {
                sources.addAnnotatedClass(entityClass);
            }
            final Metadata metadata = sources.buildMetadata();
            return new MissingVersionAttributeAudit(() -> metadata)
                    .audit(excludedEntities);
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    @Entity
    @Table(name = "versioned")
    static class VersionedEntity {
        @Id
        private Long id;
        @Version
        private long version;

        VersionedEntity() {
        }
    }

    @Entity
    @Table(name = "unversioned")
    static class UnversionedEntity {
        @Id
        private Long id;

        UnversionedEntity() {
        }
    }

    @Entity
    @Immutable
    @Table(name = "immutable_entity")
    static class ImmutableEntity {
        @Id
        private Long id;

        ImmutableEntity() {
        }
    }

    @Entity
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    @DiscriminatorColumn(name = "entity_type")
    @Table(name = "root_entity")
    static class RootEntity {
        @Id
        private Long id;

        RootEntity() {
        }
    }

    @Entity
    static class SubEntity extends RootEntity {
        private String extra;

        SubEntity() {
        }
    }
}
