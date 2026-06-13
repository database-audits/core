package io.github.databaseaudits.audit.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import jakarta.persistence.EntityManagerFactory;

class SchemaEntityValidationAuditTest {

    @Test
    void testAudit_EntityManagerFactoryPresent_ReturnsNoViolations() {
        final var audit = new SchemaEntityValidationAudit(
                mock(EntityManagerFactory.class));
        assertThat(audit.audit()).isEmpty();
    }

    @Test
    void testAudit_NoEntityManagerFactory_ReportsViolation() {
        final var audit = new SchemaEntityValidationAudit(null);
        assertThat(audit.audit()).anySatisfy(violation -> assertThat(violation)
                .contains("EntityManagerFactory"));
    }
}
