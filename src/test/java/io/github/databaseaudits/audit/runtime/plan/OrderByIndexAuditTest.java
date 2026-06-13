package io.github.databaseaudits.audit.runtime.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.databaseaudits.capture.SqlCapturingStatementInspector;
import io.github.databaseaudits.plan.QueryPlanExplainer;
import io.github.databaseaudits.platform.DatabasePlatform;

/**
 * Pure unit tests of the audit's hooks with hand-built JSON plan nodes. The
 * explainer is real but used only for its pure, no-I/O {@code textOf} helper
 * (the null DataSource is never touched); the capturer is an inert mock. The
 * {@code audit(...)} pipeline guards are covered by
 * {@link WhereClauseIndexAuditIT} (they live in the shared base class).
 */
class OrderByIndexAuditTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final OrderByIndexAudit audit = new OrderByIndexAudit(
            new QueryPlanExplainer(null, DatabasePlatform.POSTGRESQL),
            mock(SqlCapturingStatementInspector.class));

    @Test
    void testIsCandidate_SelectOrWithOrderBy_AcceptedOthersRejected() {
        assertThat(audit.isCandidate("SELECT * FROM T ORDER BY A")).isTrue();
        assertThat(audit
                .isCandidate("WITH X AS (SELECT 1) SELECT * FROM X ORDER BY A"))
                .isTrue();
        assertThat(audit.isCandidate("SELECT * FROM T")).isFalse();
        assertThat(audit.isCandidate("UPDATE T SET A = $1 WHERE B = $2"))
                .isFalse();
    }

    @Test
    void testCollectFindings_SurvivingSort_FlagsNamingSortKeyAndRelation()
            throws Exception {
        final JsonNode plan = mapper.readTree(
                """
                        {"Node Type":"Sort","Sort Key":["created_at DESC","id"],"Plans":[
                           {"Node Type":"Seq Scan","Relation Name":"orders"}]}""");
        final List<String> findings = new ArrayList<>();
        audit.collectFindings(plan, findings, Set.of());
        assertThat(findings)
                .containsExactly("Sort under 'orders' by created_at DESC, id");
    }

    @Test
    void testCollectFindings_IncrementalSort_Flags() throws Exception {
        final JsonNode plan = mapper.readTree("""
                {"Node Type":"Incremental Sort","Sort Key":["a","b"],"Plans":[
                   {"Node Type":"Index Scan","Relation Name":"events"}]}""");
        final List<String> findings = new ArrayList<>();
        audit.collectFindings(plan, findings, Set.of());
        assertThat(findings)
                .containsExactly("Incremental Sort under 'events' by a, b");
    }

    @Test
    void testCollectFindings_ExcludedRelation_NotFlagged() throws Exception {
        final JsonNode plan = mapper.readTree("""
                {"Node Type":"Sort","Sort Key":["x"],"Plans":[
                   {"Node Type":"Seq Scan","Relation Name":"audit_log"}]}""");
        final List<String> findings = new ArrayList<>();
        audit.collectFindings(plan, findings, Set.of("audit_log"));
        assertThat(findings).isEmpty();
    }

    @Test
    void testCollectFindings_SortOverDerivedData_ReportedWithoutTable()
            throws Exception {
        final JsonNode plan = mapper.readTree("""
                {"Node Type":"Sort","Sort Key":["count(*)"],"Plans":[
                   {"Node Type":"Aggregate"}]}""");
        final List<String> findings = new ArrayList<>();
        audit.collectFindings(plan, findings, Set.of());
        assertThat(findings).containsExactly("Sort by count(*)");
    }

    @Test
    void testCollectFindings_SortWithoutSortKey_ReportedWithPlaceholderKey()
            throws Exception {
        final JsonNode plan = mapper.readTree("""
                {"Node Type":"Sort","Plans":[
                   {"Node Type":"Seq Scan","Relation Name":"orders"}]}""");
        final List<String> findings = new ArrayList<>();
        audit.collectFindings(plan, findings, Set.of());
        assertThat(findings)
                .containsExactly("Sort under 'orders' by (unknown key)");
    }

    @Test
    void testCollectFindings_SortKeyNotArray_ReportedWithPlaceholderKey()
            throws Exception {
        final JsonNode plan = mapper.readTree("""
                {"Node Type":"Sort","Sort Key":"created_at","Plans":[
                   {"Node Type":"Seq Scan","Relation Name":"orders"}]}""");
        final List<String> findings = new ArrayList<>();
        audit.collectFindings(plan, findings, Set.of());
        assertThat(findings)
                .containsExactly("Sort under 'orders' by (unknown key)");
    }

    @Test
    void testCollectFindings_MissingPlanNode_IsNullSafe() {
        final List<String> findings = new ArrayList<>();
        audit.collectFindings(null, findings, Set.of());
        assertThat(findings).isEmpty();
    }

    @Test
    void testAudit_NothingCouldBeExplained_ThrowsNamingOrderByStatementShapes() {
        final SqlCapturingStatementInspector capturer =
                mock(SqlCapturingStatementInspector.class);
        final QueryPlanExplainer explainer =
                spy(new QueryPlanExplainer(null, DatabasePlatform.POSTGRESQL));
        final OrderByIndexAudit loopAudit =
                new OrderByIndexAudit(explainer, capturer);
        when(capturer.capturedSql())
                .thenReturn(Set.of("select * from t order by a"));
        when(capturer.normalize(anyString())).thenAnswer(
                invocation -> invocation.getArgument(0, String.class));
        org.mockito.Mockito
                .doThrow(new IllegalStateException("Could not EXPLAIN"))
                .when(explainer).planWith(anyString(), any(String[].class));

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> loopAudit.audit(Set.of(), List.of()))
                .withMessageContaining("ORDER BY statement shape(s)")
                .withMessageContaining("none could be EXPLAINed");
    }

    @Test
    void testAudit_SurvivingSort_PenalizesSortAndReportsFinding()
            throws Exception {
        final SqlCapturingStatementInspector capturer =
                mock(SqlCapturingStatementInspector.class);
        final QueryPlanExplainer explainer =
                spy(new QueryPlanExplainer(null, DatabasePlatform.POSTGRESQL));
        final OrderByIndexAudit loopAudit =
                new OrderByIndexAudit(explainer, capturer);
        when(capturer.capturedSql())
                .thenReturn(Set.of("select * from orders order by created_at"));
        when(capturer.normalize(anyString())).thenAnswer(
                invocation -> invocation.getArgument(0, String.class));
        doReturn(mapper.readTree("""
                {"Node Type":"Sort","Sort Key":["created_at"],"Plans":[
                   {"Node Type":"Seq Scan","Relation Name":"orders"}]}"""))
                .when(explainer).planWith(anyString(), any(String[].class));

        assertThat(loopAudit.audit(Set.of(), List.of()))
                .anySatisfy(violation -> assertThat(violation)
                        .contains("Sort under 'orders' by created_at"));

        org.mockito.Mockito.verify(explainer).planWith(
                org.mockito.ArgumentMatchers
                        .eq("select * from orders order by created_at"),
                org.mockito.ArgumentMatchers.eq("enable_sort = off"));
    }
}
