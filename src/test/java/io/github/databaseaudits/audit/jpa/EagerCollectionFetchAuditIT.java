package io.github.databaseaudits.audit.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;

import io.github.databaseaudits.audit.finding.Finding;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

/**
 * End-to-end validation of {@link EagerCollectionFetchAudit} against a real
 * Hibernate boot mapping model built from nested entity classes covering each
 * scenario the audit distinguishes. Only the mapping model is built — no DDL
 * is ever run.
 */
class EagerCollectionFetchAuditIT {

    @Test
    void testAudit_EagerOneToMany_ReportedWithRoleAndTable() {
        assertThat(audit()).extracting(Finding::description)
                .as("An eagerly fetched @OneToMany should be reported, naming its role and table.")
                .anySatisfy(description -> assertThat(description)
                        .contains(EagerOwner.class.getName() + ".eagerChildren")
                        .contains("eager_child"));
    }

    @Test
    void testAudit_LazyOneToMany_NotReported() {
        assertThat(audit()).extracting(Finding::description)
                .as("A lazily fetched @OneToMany should not be reported.")
                .noneSatisfy(description -> assertThat(description)
                        .contains("lazyChildren"));
    }

    @Test
    void testAudit_EagerManyToMany_ReportedWithRoleAndJoinTable() {
        assertThat(audit()).extracting(Finding::description)
                .as("An eagerly fetched @ManyToMany should be reported, naming its role and join table.")
                .anySatisfy(description -> assertThat(description)
                        .contains(EagerOwner.class.getName() + ".categories")
                        .contains("owner_categories"));
    }

    @Test
    void testAudit_EagerElementCollection_Reported() {
        assertThat(audit()).extracting(Finding::description)
                .as("An eagerly fetched @ElementCollection is a collection binding too, and should be reported.")
                .anySatisfy(description -> assertThat(description)
                        .contains(EagerOwner.class.getName() + ".tags")
                        .contains("owner_tags"));
    }

    @Test
    void testAudit_ExcludedRole_Suppressed() {
        final String eagerChildrenRole =
                EagerOwner.class.getName() + ".eagerChildren";
        final String categoriesRole = EagerOwner.class.getName() + ".categories";
        final String tagsRole = EagerOwner.class.getName() + ".tags";

        assertThat(audit(Set.of(eagerChildrenRole, categoriesRole, tagsRole)))
                .as("Excluding all three eager roles should leave no violations.")
                .isEmpty();
    }

    private static List<Finding> audit() {
        return audit(Set.of());
    }

    private static List<Finding> audit(final Set<String> excludedRoles) {
        // no live connection needed - only the boot mapping model is built,
        // never any DDL, but Hibernate still needs a dialect to resolve
        // column types
        final StandardServiceRegistry registry =
                new StandardServiceRegistryBuilder()
                        .applySetting("hibernate.dialect",
                                "org.hibernate.dialect.H2Dialect")
                        .build();
        try {
            final Metadata metadata = new MetadataSources(registry)
                    .addAnnotatedClass(EagerOwner.class)
                    .addAnnotatedClass(EagerChild.class)
                    .addAnnotatedClass(LazyChild.class)
                    .addAnnotatedClass(Category.class).buildMetadata();
            return new EagerCollectionFetchAudit(() -> metadata)
                    .audit(excludedRoles);
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    @Entity
    @Table(name = "eager_owner")
    static class EagerOwner {
        @Id
        private Long id;

        @OneToMany(fetch = FetchType.EAGER)
        @JoinColumn(name = "owner_id")
        private List<EagerChild> eagerChildren;

        @OneToMany(fetch = FetchType.LAZY)
        @JoinColumn(name = "owner_id")
        private List<LazyChild> lazyChildren;

        @ElementCollection(fetch = FetchType.EAGER)
        @CollectionTable(name = "owner_tags",
                joinColumns = @JoinColumn(name = "owner_id"))
        @Column(name = "tag")
        private Set<String> tags;

        @ManyToMany(fetch = FetchType.EAGER)
        @JoinTable(name = "owner_categories",
                joinColumns = @JoinColumn(name = "owner_id"),
                inverseJoinColumns = @JoinColumn(name = "category_id"))
        private List<Category> categories;

        EagerOwner() {
        }
    }

    @Entity
    @Table(name = "eager_child")
    static class EagerChild {
        @Id
        private Long id;

        EagerChild() {
        }
    }

    @Entity
    @Table(name = "category")
    static class Category {
        @Id
        private Long id;

        Category() {
        }
    }

    @Entity
    @Table(name = "lazy_child")
    static class LazyChild {
        @Id
        private Long id;

        LazyChild() {
        }
    }
}
