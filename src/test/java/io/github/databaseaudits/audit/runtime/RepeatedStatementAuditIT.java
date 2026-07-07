package io.github.databaseaudits.audit.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.databaseaudits.audit.finding.Finding;
import io.github.databaseaudits.capture.SqlCapturingStatementInspector;

/**
 * Integration of {@link RepeatedStatementAudit} with a real
 * {@link SqlCapturingStatementInspector} — every test drives {@code audit(...)}
 * through the capturer's inspect/executionCounts/normalize pipeline. No
 * database involved.
 */
class RepeatedStatementAuditIT {

    private final SqlCapturingStatementInspector capturer =
            new SqlCapturingStatementInspector();
    private final RepeatedStatementAudit audit =
            new RepeatedStatementAudit(capturer);

    private void inspectNTimes(final String sql, final int n) {
        for (int i = 0; i < n; i++) {
            capturer.inspect(sql);
        }
    }

    @Test
    void testAudit_StatementAtThreshold_Reported() {
        inspectNTimes("select o1_0.id from orders o1_0 where o1_0.id = ?", 5);

        assertThat(audit.audit(5, List.of())).extracting(Finding::description)
                .as("A statement captured exactly the threshold count should be reported.")
                .containsExactly(
                        "executed 5 times: select o1_0.id from orders o1_0 where o1_0.id = ?");
    }

    @Test
    void testAudit_StatementBelowThreshold_NotReported() {
        inspectNTimes("select o1_0.id from orders o1_0 where o1_0.id = ?", 5);

        assertThat(audit.audit(6, List.of()))
                .as("A statement captured fewer times than the threshold should not be reported.")
                .isEmpty();
    }

    @Test
    void testAudit_DifferingWhitespaceVariants_SumIntoOneNormalizedShape() {
        capturer.inspect("select o1_0.id\nfrom orders o1_0 where o1_0.id = ?");
        capturer.inspect("select o1_0.id from orders o1_0 where o1_0.id = ?");
        capturer.inspect(
                "select   o1_0.id from orders o1_0 where o1_0.id = ?");

        assertThat(audit.audit(3, List.of())).extracting(Finding::description)
                .as("Raw spellings differing only in whitespace should sum into one normalized shape.")
                .containsExactly(
                        "executed 3 times: select o1_0.id from orders o1_0 where o1_0.id = ?");
    }

    @Test
    void testAudit_RepeatedCteStatement_Reported() {
        inspectNTimes(
                "with recent as (select id from orders) select id from recent",
                5);

        assertThat(audit.audit(5, List.of())).extracting(Finding::description)
                .as("A repeated WITH (CTE) query is a read, and must be reported like a repeated SELECT.")
                .containsExactly(
                        "executed 5 times: with recent as (select id from orders) select id from recent");
    }

    @Test
    void testAudit_RepeatedUpdateStatement_NeverReported() {
        inspectNTimes("update orders set status = ? where id = ?", 10);

        assertThat(audit.audit(2, List.of()))
                .as("Only SELECT shapes are candidates, never UPDATE statements.")
                .isEmpty();
    }

    @Test
    void testAudit_ExcludedFragment_Suppressed() {
        inspectNTimes("select o1_0.id from orders o1_0 where o1_0.id = ?", 5);

        assertThat(audit.audit(5, List.of("from orders"))).isEmpty();
    }

    @Test
    void testAudit_ThresholdBelowTwo_ThrowsIllegalArgumentException() {
        assertThatIllegalArgumentException()
                .as("Threshold validation should be checked even before the capture, so this throws with an empty capture too.")
                .isThrownBy(() -> audit.audit(1, List.of()));
    }

    @Test
    void testAudit_EmptyCapture_ThrowsRatherThanReportingVacuously() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> audit.audit(2, List.of()))
                .withMessageContaining("No SQL was captured");
    }

    @Test
    void testAudit_MultipleRepeatedStatements_OrderedByCountDescending() {
        inspectNTimes("select a1_0.id from a a1_0 where a1_0.id = ?", 3);
        inspectNTimes("select b1_0.id from b b1_0 where b1_0.id = ?", 7);

        assertThat(audit.audit(2, List.of())).extracting(Finding::description)
                .as("Findings should be ordered by capture count descending.")
                .containsExactly(
                        "executed 7 times: select b1_0.id from b b1_0 where b1_0.id = ?",
                        "executed 3 times: select a1_0.id from a a1_0 where a1_0.id = ?");
    }
}
