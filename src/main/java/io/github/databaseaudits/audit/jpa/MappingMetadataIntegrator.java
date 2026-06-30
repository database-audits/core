package io.github.databaseaudits.audit.jpa;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.hibernate.boot.Metadata;
import org.hibernate.boot.spi.BootstrapContext;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.service.spi.SessionFactoryServiceRegistry;
import org.jspecify.annotations.Nullable;

import jakarta.persistence.EntityManagerFactory;

/**
 * Records each persistence unit's boot-time {@link Metadata} so
 * {@link SchemaEntityValidationAudit} can validate the entity mappings against
 * the live schema and report every mismatch at once.
 *
 * <p>
 * Hibernate discards the boot {@link Metadata} once a session factory is built,
 * so the only way to recover the fully resolved mapping model (physical table and
 * column names, JDBC type codes) after startup is to capture it during bootstrap.
 * This {@link Integrator} is auto-registered for every session factory built in
 * the JVM through its
 * {@code META-INF/services/org.hibernate.integrator.spi.Integrator} entry — the
 * same mechanism Hibernate's own modules use — and stashes each factory's
 * {@link Metadata} under the factory's {@linkplain SessionFactoryImplementor#getUuid()
 * UUID}. It performs no other work, so it has no effect on the runtime.
 *
 * <p>
 * The {@link Metadata} is keyed by UUID rather than by the factory object because
 * a managed {@link EntityManagerFactory} is often a proxy (Spring wraps it): the
 * proxy is a different object from the native factory the integrator captured,
 * but it forwards {@link SessionFactoryImplementor#getUuid()} to that native
 * factory, so the UUID matches on both sides.
 * {@link SchemaEntityValidationAudit#forEntityManagerFactory} looks the captured
 * {@link Metadata} back up with {@link #metadataFor(EntityManagerFactory)}.
 */
public final class MappingMetadataIntegrator implements Integrator {
    private static final Map<String, Metadata> METADATA_BY_UUID =
            new ConcurrentHashMap<>();

    /**
     * Records the boot {@link Metadata} for the factory being built.
     *
     * @param metadata
     *                            the fully resolved boot mapping model.
     * @param bootstrapContext
     *                            the bootstrap context (unused).
     * @param sessionFactory
     *                            the factory the {@code metadata} was built for;
     *                            its UUID is the key.
     */
    @Override
    public void integrate(final Metadata metadata,
            final BootstrapContext bootstrapContext,
            final SessionFactoryImplementor sessionFactory) {
        METADATA_BY_UUID.put(sessionFactory.getUuid(), metadata);
    }

    /**
     * Drops the recorded {@link Metadata} when its factory is closed.
     *
     * @param sessionFactory
     *                            the factory being closed.
     * @param serviceRegistry
     *                            the factory's service registry (unused).
     */
    @Override
    public void disintegrate(final SessionFactoryImplementor sessionFactory,
            final SessionFactoryServiceRegistry serviceRegistry) {
        METADATA_BY_UUID.remove(sessionFactory.getUuid());
    }

    /**
     * Returns the boot {@link Metadata} captured for the given factory.
     *
     * @param entityManagerFactory
     *                                 the factory whose mapping model is wanted;
     *                                 may be a proxy over the native factory.
     * @return the captured {@link Metadata}, or {@code null} if none was recorded
     *         — for instance when the factory was built without this module on
     *         its classpath, or after the factory has been closed.
     * @throws IllegalStateException
     *                                   if the factory is not a Hibernate
     *                                   {@code SessionFactory}, so its UUID cannot
     *                                   be resolved for the lookup.
     */
    public static @Nullable Metadata metadataFor(
            final EntityManagerFactory entityManagerFactory) {
        Objects.requireNonNull(entityManagerFactory,
                "entityManagerFactory must not be null");
        final SessionFactoryImplementor sessionFactory;
        try {
            sessionFactory =
                    entityManagerFactory.unwrap(SessionFactoryImplementor.class);
        } catch (final RuntimeException e) {
            throw new IllegalStateException(
                    "EntityManagerFactory is not a Hibernate SessionFactory, so its JPA mapping "
                            + "metadata cannot be looked up for validation.",
                    e);
        }
        return METADATA_BY_UUID.get(sessionFactory.getUuid());
    }
}
