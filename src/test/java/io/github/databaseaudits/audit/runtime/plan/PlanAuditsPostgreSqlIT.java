package io.github.databaseaudits.audit.runtime.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.databaseaudits.capture.SqlCapturingStatementInspector;
import io.github.databaseaudits.fixture.DatabaseContainers;
import io.github.databaseaudits.platform.DatabasePlatform;
import io.github.databaseaudits.plan.QueryPlanExplainer;

/**
 * End-to-end run of the EXPLAIN-driven plan audits against a real PostgreSQL 16
 * container — the first execution of {@link QueryPlanExplainer}'s generic-plan
 * EXPLAIN (placeholder rewrite, penalized planner settings, RESET) outside unit
 * tests. Deliberately PostgreSQL-specific: the technique exists on no other
 * platform, and the other platforms' fail-fast is covered by
 * {@link WhereClauseIndexAuditIT} without a database.
 *
 * <p>
 * No test data is needed — {@code EXPLAIN (GENERIC_PLAN)} plans without
 * executing, and each violation is proven by plan shape under a penalized
 * setting: a {@code Seq Scan} with a {@code Filter} surviving
 * {@code enable_seqscan=off} (no index can serve the predicate), a {@code Sort}
 * surviving {@code enable_sort=off} (no index can provide the ordering), a
 * penalized join shape surviving
 * {@code enable_hashjoin/enable_mergejoin/enable_seqscan=off} (no index can
 * serve the join key). Statements are fed to the capturer directly, exactly as
 * Hibernate would during a consumer's repository workload; each test passes
 * only when its audit reports the planted violation, and passes once it is
 * excluded.
 */
class PlanAuditsPostgreSqlIT {
    private static final DataSource DATA_SOURCE =
            DatabaseContainers.postgreSqlDataSource();

    private final SqlCapturingStatementInspector capturer =
            new SqlCapturingStatementInspector();
    private final QueryPlanExplainer queryPlanExplainer =
            new QueryPlanExplainer(DATA_SOURCE, DatabasePlatform.POSTGRESQL);

    @BeforeAll
    static void createWorkloadTables() throws SQLException {
        try (Connection connection = DATA_SOURCE.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE plan_flagged (
                        id         BIGINT PRIMARY KEY,
                        data_value BIGINT,
                        sort_value BIGINT
                    )""");
            statement.execute("""
                    CREATE TABLE plan_indexed (
                        id         BIGINT PRIMARY KEY,
                        data_value BIGINT,
                        sort_value BIGINT
                    )""");
            statement.execute(
                    "CREATE INDEX idx_plan_indexed_data ON plan_indexed(data_value)");
            statement.execute(
                    "CREATE INDEX idx_plan_indexed_sort ON plan_indexed(sort_value)");
            // deterministic planner statistics for the generic plans
            statement.execute("ANALYZE plan_flagged");
            statement.execute("ANALYZE plan_indexed");
        }
    }

    @Test
    void testAudit_WhereOnUnindexedColumn_ReportedThenEmptyWhenRelationExcluded() {
        capturer.inspect("select * from plan_flagged where data_value = ?");
        capturer.inspect("select * from plan_indexed where data_value = ?");
        final var audit =
                new WhereClauseIndexAudit(queryPlanExplainer, capturer);

        assertThat(audit.audit(Set.of(), List.of()))
                .anySatisfy(violation -> assertThat(violation.description())
                        .contains("Seq Scan on 'plan_flagged'"))
                .noneSatisfy(violation -> assertThat(violation.description())
                        .contains("plan_indexed"));
        assertThat(audit.audit(Set.of("plan_flagged"), List.of())).isEmpty();
    }

    @Test
    void testAudit_OrderByOnUnindexedColumn_ReportedThenEmptyWhenFragmentExcluded() {
        capturer.inspect("select * from plan_flagged order by sort_value");
        capturer.inspect("select * from plan_indexed order by sort_value");
        final var audit = new OrderByIndexAudit(queryPlanExplainer, capturer);

        assertThat(audit.audit(Set.of(), List.of()))
                .anySatisfy(violation -> assertThat(violation.description())
                        .contains("Sort under 'plan_flagged'"))
                .noneSatisfy(violation -> assertThat(violation.description())
                        .contains("plan_indexed"));
        assertThat(audit.audit(Set.of(), List.of("from plan_flagged")))
                .isEmpty();
    }

    /**
     * The self join keys on {@code plan_flagged.data_value}, which no index
     * serves on either side, so a penalized join shape survives; the second
     * join reaches {@code plan_indexed.data_value}'s index and plans as a
     * nested loop over an inner Index Scan, proving the audit reports only the
     * unindexed join.
     */
    @Test
    void testAudit_JoinOnUnindexedColumns_ReportedThenEmptyWhenRelationExcluded() {
        capturer.inspect(
                "select f.id from plan_flagged f join plan_flagged f2 on f.data_value = f2.data_value");
        capturer.inspect(
                "select f.id from plan_flagged f join plan_indexed i on f.data_value = i.data_value");
        final var audit = new JoinIndexAudit(queryPlanExplainer, capturer);

        assertThat(audit.audit(Set.of(), List.of()))
                .anySatisfy(violation -> assertThat(violation.description())
                        .contains("on 'plan_flagged'"))
                .noneSatisfy(violation -> assertThat(violation.description())
                        .contains("plan_indexed"));
        assertThat(audit.audit(Set.of("plan_flagged"), List.of())).isEmpty();
    }
}
