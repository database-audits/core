package io.github.databaseaudits.audit.jpa;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.hibernate.boot.Metadata;
import org.hibernate.mapping.Collection;
import org.jspecify.annotations.Nullable;

import io.github.databaseaudits.audit.finding.EagerCollectionFetchFinding;
import io.github.databaseaudits.audit.finding.Finding;

import jakarta.persistence.EntityManagerFactory;

/**
 * Every mapped collection should be fetched lazily.
 *
 * <p>
 * {@code @OneToMany}/{@code @ManyToMany(fetch = EAGER)} (the JPA default for
 * {@code @ElementCollection}, but never for {@code @OneToMany}/
 * {@code @ManyToMany}) loads the collection on <em>every</em> load of its
 * owning entity — multiplying rows through a join or firing an extra query
 * per owner, compounding across every entity that reaches the collection.
 * Reads the boot mapping model from a {@link MappingMetadataIntegrator}
 * captured during bootstrap, exactly like {@link SchemaEntityValidationAudit}.
 * Advisory: pass a deliberately eager collection's Hibernate role (e.g.
 * {@code com.acme.Order.items}) as {@code excludedRoles}.
 *
 * <p>
 * Fix: switch to {@code FetchType.LAZY}, and fetch eagerly only where a
 * specific query genuinely needs the collection, via a fetch join or
 * {@code @EntityGraph}.
 */
public class EagerCollectionFetchAudit {
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
    public EagerCollectionFetchAudit(
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
    public static EagerCollectionFetchAudit forEntityManagerFactory(
            final EntityManagerFactory entityManagerFactory) {
        return new EagerCollectionFetchAudit(() -> MappingMetadataIntegrator
                .metadataFor(entityManagerFactory));
    }

    /**
     * Returns one {@link Finding} for every mapped collection fetched eagerly,
     * except the excluded ones; an empty list when every collection is lazy.
     *
     * @param excludedRoles
     *                          the collection roles to skip (e.g.
     *                          {@code com.acme.Order.items}), matched
     *                          case-insensitively.
     * @return one {@link Finding} per eagerly fetched collection — its
     *         {@link Finding#description() description} is the reported line;
     *         an empty list when every collection is lazy.
     * @throws IllegalStateException
     *                                   if the boot mapping model was never
     *                                   captured, so the audit cannot run.
     */
    public List<Finding> audit(final Set<String> excludedRoles) {
        final Metadata metadata = metadataSupplier.get();
        if (metadata == null) {
            throw new IllegalStateException(
                    "JPA mapping metadata was not captured for this EntityManagerFactory; ensure "
                            + "database-audits-core is on the test classpath (it registers a Hibernate "
                            + "Integrator that records the boot metadata) and that the EntityManagerFactory "
                            + "has been built.");
        }
        final Set<String> excludes = excludedRoles.stream()
                .map(role -> role.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        return metadata.getCollectionBindings().stream()
                .filter(binding -> !binding.isLazy())
                .filter(binding -> !excludes
                        .contains(binding.getRole().toLowerCase(Locale.ROOT)))
                .sorted(Comparator.comparing(Collection::getRole))
                .<Finding>map(binding -> new EagerCollectionFetchFinding(
                        binding.getRole(),
                        binding.getCollectionTable().getName()))
                .toList();
    }
}
