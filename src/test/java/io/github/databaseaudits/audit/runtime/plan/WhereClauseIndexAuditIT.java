package io.github.databaseaudits.audit.runtime.plan;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.databaseaudits.capture.SqlCapturingStatementInspector;
import io.github.databaseaudits.plan.QueryPlanExplainer;
import io.github.databaseaudits.platform.DatabasePlatform;

/**
 * Integration of the runtime-audit pipeline — a real audit, a real
 * {@link SqlCapturingStatementInspector}, and a real {@link QueryPlanExplainer}
 * collaborating through {@code CapturedSqlPlanAuditTemplate.audit(...)}. No
 * database: these cover the pipeline's guard paths (empty capture,
 * wholly-unexplainable run, unsupported platform), which exist precisely so a
 * broken environment throws instead of passing vacuously. The guards live in
 * the shared base class, so one audit's coverage covers both plan audits.
 */
class WhereClauseIndexAuditIT {
    private final SqlCapturingStatementInspector capturer =
            new SqlCapturingStatementInspector();
    private final WhereClauseIndexAudit audit = new WhereClauseIndexAudit(
            new QueryPlanExplainer(null, DatabasePlatform.POSTGRESQL),
            capturer);

    @Test
    void testAudit_EmptyCapture_Throws() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> audit.audit(Set.of(), List.of()))
                .withMessageContaining("No SQL was captured");
    }

    @Test
    void testAudit_CapturedButNoneExplainable_Throws() {
        // candidate statement, but no DataSource, so EXPLAIN can't run and
        // every statement is skipped
        capturer.inspect("select * from t where a = ?");
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> audit.audit(Set.of(), List.of()))
                .withMessageContaining("none could be EXPLAINed");
    }

    @Test
    void testAudit_NonPostgresPlatform_ThrowsNamingAuditAndPlatform() {
        final WhereClauseIndexAudit mysqlAudit = new WhereClauseIndexAudit(
                new QueryPlanExplainer(null, DatabasePlatform.MYSQL), capturer);
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> mysqlAudit.audit(Set.of(), List.of()))
                .withMessageContaining("WhereClauseIndexAudit")
                .withMessageContaining("MYSQL")
                .withMessageContaining("PostgreSQL 16+");
    }
}
