package io.github.databaseaudits.audit.jpa;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Pure unit test of the audit's cannot-run guard. The end-to-end mapping walk
 * is covered by {@link EagerCollectionFetchAuditIT}.
 */
class EagerCollectionFetchAuditTest {
    @Test
    void testAudit_MetadataNeverCaptured_ThrowsRatherThanReportingVacuously() {
        final var audit = new EagerCollectionFetchAudit(() -> null);

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> audit.audit(Set.of()))
                .withMessageContaining("metadata was not captured");
    }
}
