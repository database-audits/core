package io.github.databaseaudits.audit.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.databaseaudits.capture.SqlCapturingStatementInspector;

/**
 * Integration of {@link UnconditionalMutationAudit} with a real
 * {@link SqlCapturingStatementInspector} — every test drives {@code audit(...)}
 * through the capturer's inspect/normalize pipeline. No database involved.
 */
class UnconditionalMutationAuditIT {

    private final SqlCapturingStatementInspector capturer =
            new SqlCapturingStatementInspector();
    private final UnconditionalMutationAudit audit =
            new UnconditionalMutationAudit(capturer);

    @Test
    void testAudit_UpdateOrDeleteWithoutWhere_ReportsThem() {
        capturer.inspect("delete from orders");
        capturer.inspect("update orders set total = 0");
        assertThat(audit.audit(Set.of())).containsExactly("delete from orders",
                "update orders set total = 0");
    }

    @Test
    void testAudit_EveryMutationHasWhere_ReturnsNoViolations() {
        capturer.inspect("delete from orders where id = ?");
        capturer.inspect("update orders set total = ? where id = ?");
        assertThat(audit.audit(Set.of())).isEmpty();
    }

    @Test
    void testAudit_NonMutationStatementCaptured_ReturnsNoViolations() {
        capturer.inspect("select * from orders where id = ?");
        capturer.inspect("delete from orders where id = ?");
        assertThat(audit.audit(Set.of())).isEmpty();
    }

    @Test
    void testAudit_ExcludedFullTableStatement_ReturnsNoViolations() {
        capturer.inspect("delete from scratch_buffer");
        assertThat(audit.audit(Set.of("delete from scratch_buffer"))).isEmpty();
    }

    @Test
    void testAudit_EmptyCapture_ThrowsRatherThanReportingVacuously() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> audit.audit(Set.of()))
                .withMessageContaining("No SQL was captured");
    }
}
