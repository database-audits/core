package io.github.databaseaudits.audit.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.databaseaudits.audit.finding.Finding;
import io.github.databaseaudits.capture.SqlCapturingStatementInspector;

/**
 * Integration of {@link OffsetPaginationAudit} with a real
 * {@link SqlCapturingStatementInspector} — every test drives {@code audit(...)}
 * through the capturer's inspect/normalize pipeline. No database involved.
 */
class OffsetPaginationAuditIT {

    private final SqlCapturingStatementInspector capturer =
            new SqlCapturingStatementInspector();
    private final OffsetPaginationAudit audit =
            new OffsetPaginationAudit(capturer);

    @Test
    void testAudit_LimitOffsetForm_Reported() {
        capturer.inspect("select o1_0.id from orders o1_0 limit ? offset ?");

        assertThat(audit.audit(List.of())).extracting(Finding::description)
                .as("The limit/offset form should be reported.")
                .containsExactly(
                        "select o1_0.id from orders o1_0 limit ? offset ?");
    }

    @Test
    void testAudit_StandardOffsetFetchForm_Reported() {
        capturer.inspect(
                "select o1_0.id from orders o1_0 offset ? rows fetch first ? rows only");

        assertThat(audit.audit(List.of())).extracting(Finding::description)
                .as("The standard OFFSET ... FETCH FIRST form Hibernate 6 emits should be reported.")
                .containsExactly(
                        "select o1_0.id from orders o1_0 offset ? rows fetch first ? rows only");
    }

    @Test
    void testAudit_MysqlCommaForm_Reported() {
        capturer.inspect("select o1_0.id from orders o1_0 limit ?, ?");

        assertThat(audit.audit(List.of())).extracting(Finding::description)
                .as("The MySQL/MariaDB comma limit-offset form should be reported.")
                .containsExactly("select o1_0.id from orders o1_0 limit ?, ?");
    }

    @Test
    void testAudit_BareLimitWithoutOffset_NotReported() {
        capturer.inspect("select o1_0.id from orders o1_0 limit ?");

        assertThat(audit.audit(List.of()))
                .as("A bare LIMIT with no offset is not offset pagination.")
                .isEmpty();
    }

    @Test
    void testAudit_FetchFirstWithoutOffset_NotReported() {
        capturer.inspect(
                "select o1_0.id from orders o1_0 fetch first ? rows only");

        assertThat(audit.audit(List.of()))
                .as("FETCH FIRST with no preceding OFFSET is not offset pagination.")
                .isEmpty();
    }

    @Test
    void testAudit_UpdateStatement_NeverReported() {
        capturer.inspect(
                "update orders set status = ? where id in (select id from orders limit ? offset ?)");

        assertThat(audit.audit(List.of()))
                .as("Only SELECT/WITH queries are candidates, never UPDATE statements.")
                .isEmpty();
    }

    @Test
    void testAudit_ExcludedFragment_Suppressed() {
        capturer.inspect("select o1_0.id from orders o1_0 limit ? offset ?");

        assertThat(audit.audit(List.of("from orders")))
                .as("Excluding the SQL fragment should suppress the finding.")
                .isEmpty();
    }

    @Test
    void testAudit_EmptyCapture_ThrowsRatherThanReportingVacuously() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> audit.audit(List.of()))
                .withMessageContaining("No SQL was captured");
    }
}
