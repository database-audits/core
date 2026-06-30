package io.github.databaseaudits.audit.jpa;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import jakarta.persistence.EntityManagerFactory;

class SchemaEntityValidationAuditTest {

    @Test
    void testAudit_MetadataNotCaptured_ThrowsCannotRun() {
        final var audit = new SchemaEntityValidationAudit(() -> null,
                mock(DataSource.class));
        assertThatThrownBy(audit::audit)
                .as("Missing boot metadata is a cannot-run condition, not a violation.")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mapping metadata was not captured");
    }

    @Test
    void testMetadataFor_NotAHibernateSessionFactory_ThrowsClearError() {
        final EntityManagerFactory entityManagerFactory =
                mock(EntityManagerFactory.class);
        when(entityManagerFactory.unwrap(SessionFactoryImplementor.class))
                .thenThrow(new IllegalArgumentException("not a SessionFactory"));
        assertThatThrownBy(
                () -> MappingMetadataIntegrator.metadataFor(entityManagerFactory))
                .as("An EntityManagerFactory that is not a Hibernate SessionFactory should fail "
                        + "with a clear message rather than a raw unwrap error.")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a Hibernate SessionFactory");
    }
}
