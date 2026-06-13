package io.github.databaseaudits.audit.jpa;

import java.util.List;

import jakarta.persistence.EntityManagerFactory;
import lombok.AllArgsConstructor;

/**
 * JPA mapping audit — verifies that entity mappings match the schema by
 * confirming Hibernate startup validation succeeded.
 *
 * <p>
 * The real check runs at startup when Hibernate schema validation is enabled
 * with {@code hibernate.hbm2ddl.auto=validate} (Spring Boot:
 * {@code spring.jpa.hibernate.ddl-auto=validate}): a successful
 * {@link jakarta.persistence.EntityManagerFactory} build proves every mapped
 * entity was validated against its table and columns, or the
 * {@link EntityManagerFactory} fails to build and the context never starts.
 *
 * <p>
 * Reaching this audit means validation passed — so the paired {@code @Test}
 * must enable validation (e.g. via {@code @TestPropertySource} in Spring) for
 * it to mean anything; the non-null {@link EntityManagerFactory} is the
 * formality proving the factory was built under it.
 *
 * <p>
 * Fix: reconcile the entity mappings with the Liquibase-built schema (whichever
 * drifted).
 */
@AllArgsConstructor
public class SchemaEntityValidationAudit {
    private final EntityManagerFactory entityManagerFactory;

    /**
     * Returns a single violation when the {@link EntityManagerFactory} was not
     * built (so startup validation never ran); an empty list when it was —
     * which, under {@code ddl-auto=validate}, means the mappings matched the
     * schema.
     */
    public List<String> audit() {
        if (entityManagerFactory == null) {
            return List.of(
                    "EntityManagerFactory should have been built under ddl-auto=validate.");
        }
        return List.of();
    }
}
