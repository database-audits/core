package io.github.databaseaudits.audit.jpa;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.hibernate.boot.Metadata;
import org.hibernate.mapping.PersistentClass;
import org.jspecify.annotations.Nullable;

import io.github.databaseaudits.audit.finding.Finding;
import io.github.databaseaudits.audit.finding.MissingVersionAttributeFinding;

import jakarta.persistence.EntityManagerFactory;

/**
 * Every mutable root entity should carry a {@code @Version} attribute.
 *
 * <p>
 * A mutable entity with no optimistic-locking version lets concurrent
 * transactions silently overwrite each other's changes (a lost update):
 * transaction A reads a row, transaction B reads and updates the same row,
 * then A's later update blindly clobbers B's change with no conflict
 * detection. Versioning is declared on the inheritance hierarchy's root, so
 * only root {@link PersistentClass} bindings are checked; an {@code @Immutable}
 * entity is skipped, since it cannot lose an update it never receives. Reads
 * the boot mapping model from a {@link MappingMetadataIntegrator} captured
 * during bootstrap, exactly like {@link SchemaEntityValidationAudit}.
 * Advisory: append-only or single-writer entities are legitimate exclusions —
 * pass them (fully-qualified name, simple name, or physical table name) as
 * {@code excludedEntities}.
 *
 * <p>
 * Fix: add a {@code @Version} attribute, or exclude the entity if it is
 * genuinely append-only or single-writer.
 */
public class MissingVersionAttributeAudit {
    private final Supplier<@Nullable Metadata> metadataSupplier;

    /**
     * Constructs the audit around the boot mapping model.
     *
     * @param metadataSupplier
     *                             supplies the boot {@link Metadata} for the
     *                             persistence unit, resolved when
     *                             {@link #audit(Set)} runs; see
     *                             {@link #forEntityManagerFactory(EntityManagerFactory)}.
     */
    public MissingVersionAttributeAudit(
            final Supplier<@Nullable Metadata> metadataSupplier) {
        this.metadataSupplier = metadataSupplier;
    }

    /**
     * Builds an audit for a JPA {@link EntityManagerFactory}, resolving its boot
     * {@link Metadata} from {@link MappingMetadataIntegrator} when the audit
     * runs.
     *
     * @param entityManagerFactory
     *                                 the factory whose mappings to validate.
     * @return the audit.
     */
    public static MissingVersionAttributeAudit forEntityManagerFactory(
            final EntityManagerFactory entityManagerFactory) {
        return new MissingVersionAttributeAudit(() -> MappingMetadataIntegrator
                .metadataFor(entityManagerFactory));
    }

    /**
     * Returns one {@link Finding} for every mutable root entity with no
     * {@code @Version} attribute, except the excluded ones; an empty list when
     * every mutable root entity is versioned.
     *
     * @param excludedEntities
     *                             the entities to skip, matched
     *                             case-insensitively against the entity's
     *                             fully-qualified name, its simple name, or its
     *                             physical table name.
     * @return one {@link Finding} per unversioned mutable root entity — its
     *         {@link Finding#description() description} is the reported line;
     *         an empty list when every mutable root entity is versioned.
     * @throws IllegalStateException
     *                                   if the boot mapping model was never
     *                                   captured, so the audit cannot run.
     */
    public List<Finding> audit(final Set<String> excludedEntities) {
        final Metadata metadata = metadataSupplier.get();
        if (metadata == null) {
            throw new IllegalStateException(
                    "JPA mapping metadata was not captured for this EntityManagerFactory; ensure "
                            + "database-audits-core is on the test classpath (it registers a Hibernate "
                            + "Integrator that records the boot metadata) and that the EntityManagerFactory "
                            + "has been built.");
        }
        final Set<String> excludes = excludedEntities.stream()
                .map(e -> e.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        return metadata.getEntityBindings().stream()
                .filter(persistentClass -> persistentClass
                        .getSuperclass() == null)
                .filter(PersistentClass::isMutable)
                .filter(persistentClass -> !persistentClass.isVersioned())
                .filter(persistentClass -> !isExcluded(persistentClass,
                        excludes))
                .sorted(Comparator.comparing(PersistentClass::getEntityName))
                .<Finding>map(
                        persistentClass -> new MissingVersionAttributeFinding(
                                persistentClass.getEntityName(),
                                persistentClass.getTable().getName()))
                .toList();
    }

    private boolean isExcluded(final PersistentClass persistentClass,
            final Set<String> excludes) {
        final String entityName =
                persistentClass.getEntityName().toLowerCase(Locale.ROOT);
        // split on the last '.' or '$' - a nested class's binary name uses
        // '$' before its own simple name, not '.'
        final int lastSeparator = Math.max(entityName.lastIndexOf('.'),
                entityName.lastIndexOf('$'));
        final String simpleName = lastSeparator < 0 ? entityName
                : entityName.substring(lastSeparator + 1);
        final String tableName = persistentClass.getTable().getName()
                .toLowerCase(Locale.ROOT);
        return excludes.contains(entityName) || excludes.contains(simpleName)
                || excludes.contains(tableName);
    }
}
