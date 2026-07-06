package io.github.databaseaudits.audit.runtime.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.databaseaudits.capture.SqlCapturingStatementInspector;
import io.github.databaseaudits.plan.QueryPlanExplainer;
import io.github.databaseaudits.platform.DatabasePlatform;

/**
 * Pure unit tests of the audit's hooks with hand-built JSON plan nodes, and of
 * the {@code audit(...)} loop with a mocked capturer and a spied explainer
 * whose {@code planWith} is stubbed (no I/O; the real {@code textOf} pure
 * helper runs). The pipeline guards with real collaborators are covered by
 * {@link WhereClauseIndexAuditIT} (shared base class); the end-to-end path runs
 * in {@link PlanAuditsPostgreSqlIT}.
 */
class JoinIndexAuditTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JoinIndexAudit audit = new JoinIndexAudit(
            new QueryPlanExplainer(null, DatabasePlatform.POSTGRESQL),
            mock(SqlCapturingStatementInspector.class));

    @Test
    void testIsCandidate_QueryWithJoin_AcceptedOthersRejected() {
        assertThat(audit.isCandidate(
                "SELECT O.ID FROM ORDERS O JOIN CUSTOMERS C ON O.CUSTOMER_ID = C.ID"))
                .isTrue();
        assertThat(audit.isCandidate(
                "WITH X AS (SELECT 1) SELECT * FROM A JOIN X ON A.ID = X.ID"))
                .isTrue();
        assertThat(audit
                .isCandidate("SELECT A.* FROM A LEFT JOIN B ON A.ID = B.A_ID"))
                .isTrue();
        assertThat(audit.isCandidate("SELECT * FROM ORDERS WHERE ID = $1"))
                .isFalse();
        assertThat(audit.isCandidate("INSERT INTO T VALUES ($1)")).isFalse();
    }

    @Test
    void testCollectFindings_SurvivingHashJoin_FlagsInnerRelation()
            throws Exception {
        final JsonNode plan = mapper.readTree(
                """
                        {"Node Type":"Hash Join","Hash Cond":"(o.customer_id = c.id)","Plans":[
                           {"Node Type":"Seq Scan","Parent Relationship":"Outer","Relation Name":"orders"},
                           {"Node Type":"Hash","Parent Relationship":"Inner","Plans":[
                              {"Node Type":"Seq Scan","Parent Relationship":"Outer","Relation Name":"customers"}]}]}""");
        final List<String> findings = new ArrayList<>();
        audit.collectFindings(plan, findings, Set.of());
        assertThat(findings).containsExactly(
                "Hash Join on 'customers' joining (o.customer_id = c.id)");
    }

    @Test
    void testCollectFindings_SurvivingMergeJoin_FlagsInnerRelationThroughSort()
            throws Exception {
        final JsonNode plan = mapper.readTree(
                """
                        {"Node Type":"Merge Join","Merge Cond":"(o.customer_id = c.id)","Plans":[
                           {"Node Type":"Sort","Parent Relationship":"Outer","Plans":[
                              {"Node Type":"Seq Scan","Parent Relationship":"Outer","Relation Name":"orders"}]},
                           {"Node Type":"Sort","Parent Relationship":"Inner","Plans":[
                              {"Node Type":"Seq Scan","Parent Relationship":"Outer","Relation Name":"customers"}]}]}""");
        final List<String> findings = new ArrayList<>();
        audit.collectFindings(plan, findings, Set.of());
        assertThat(findings).containsExactly(
                "Merge Join on 'customers' joining (o.customer_id = c.id)");
    }

    @Test
    void testCollectFindings_NestedLoopWithInnerSeqScan_Flags()
            throws Exception {
        final JsonNode plan = mapper.readTree(
                """
                        {"Node Type":"Nested Loop","Join Filter":"(o.customer_id = c.id)","Plans":[
                           {"Node Type":"Seq Scan","Parent Relationship":"Outer","Relation Name":"orders"},
                           {"Node Type":"Seq Scan","Parent Relationship":"Inner","Relation Name":"customers"}]}""");
        final List<String> findings = new ArrayList<>();
        audit.collectFindings(plan, findings, Set.of());
        assertThat(findings).containsExactly(
                "Nested Loop with inner Seq Scan on 'customers' joining (o.customer_id = c.id)");
    }

    @Test
    void testCollectFindings_NestedLoopInnerSeqScanBehindMaterialize_Flags()
            throws Exception {
        final JsonNode plan = mapper.readTree(
                """
                        {"Node Type":"Nested Loop","Join Filter":"(o.customer_id = c.id)","Plans":[
                           {"Node Type":"Seq Scan","Parent Relationship":"Outer","Relation Name":"orders"},
                           {"Node Type":"Materialize","Parent Relationship":"Inner","Plans":[
                              {"Node Type":"Seq Scan","Parent Relationship":"Outer","Relation Name":"customers"}]}]}""");
        final List<String> findings = new ArrayList<>();
        audit.collectFindings(plan, findings, Set.of());
        assertThat(findings).containsExactly(
                "Nested Loop with inner Seq Scan on 'customers' joining (o.customer_id = c.id)");
    }

    @Test
    void testCollectFindings_NestedLoopInnerSeqScanWithConditionOnScanFilter_FlagsUsingThatFilter()
            throws Exception {
        final JsonNode plan = mapper.readTree(
                """
                        {"Node Type":"Nested Loop","Plans":[
                           {"Node Type":"Seq Scan","Parent Relationship":"Outer","Relation Name":"orders"},
                           {"Node Type":"Seq Scan","Parent Relationship":"Inner","Relation Name":"customers",
                            "Filter":"(c.id = o.customer_id)"}]}""");
        final List<String> findings = new ArrayList<>();
        audit.collectFindings(plan, findings, Set.of());
        assertThat(findings).containsExactly(
                "Nested Loop with inner Seq Scan on 'customers' joining (c.id = o.customer_id)");
    }

    @Test
    void testCollectFindings_NestedLoopWithInnerIndexScan_Ignored()
            throws Exception {
        final JsonNode plan = mapper.readTree(
                """
                        {"Node Type":"Nested Loop","Plans":[
                           {"Node Type":"Seq Scan","Parent Relationship":"Outer","Relation Name":"orders"},
                           {"Node Type":"Index Scan","Parent Relationship":"Inner","Relation Name":"customers",
                            "Index Cond":"(c.id = o.customer_id)"}]}""");
        final List<String> findings = new ArrayList<>();
        audit.collectFindings(plan, findings, Set.of());
        assertThat(findings).isEmpty();
    }

    @Test
    void testCollectFindings_ExcludedRelation_NotFlagged() throws Exception {
        final JsonNode plan = mapper.readTree(
                """
                        {"Node Type":"Nested Loop","Join Filter":"(o.country_code = c.code)","Plans":[
                           {"Node Type":"Seq Scan","Parent Relationship":"Outer","Relation Name":"orders"},
                           {"Node Type":"Seq Scan","Parent Relationship":"Inner","Relation Name":"country"}]}""");
        final List<String> findings = new ArrayList<>();
        audit.collectFindings(plan, findings, Set.of("country"));
        assertThat(findings).isEmpty();
    }

    @Test
    void testCollectFindings_ChildPlans_RecursesIntoThem() throws Exception {
        final JsonNode plan = mapper.readTree(
                """
                        {"Node Type":"Limit","Plans":[
                           {"Node Type":"Nested Loop","Join Filter":"(o.customer_id = c.id)","Plans":[
                              {"Node Type":"Seq Scan","Parent Relationship":"Outer","Relation Name":"orders"},
                              {"Node Type":"Seq Scan","Parent Relationship":"Inner","Relation Name":"customers"}]}]}""");
        final List<String> findings = new ArrayList<>();
        audit.collectFindings(plan, findings, Set.of());
        assertThat(findings).containsExactly(
                "Nested Loop with inner Seq Scan on 'customers' joining (o.customer_id = c.id)");
    }

    @Test
    void testCollectFindings_MissingPlanNode_IsNullSafe() {
        final List<String> findings = new ArrayList<>();
        audit.collectFindings(null, findings, Set.of());
        assertThat(findings).isEmpty();
    }

    // --- audit(...) loop, with planWith stubbed ---

    private final SqlCapturingStatementInspector capturer =
            mock(SqlCapturingStatementInspector.class);
    private final QueryPlanExplainer explainer =
            spy(new QueryPlanExplainer(null, DatabasePlatform.POSTGRESQL));
    private final JoinIndexAudit loopAudit =
            new JoinIndexAudit(explainer, capturer);

    private void captured(final String... statements) {
        when(capturer.capturedSql())
                .thenReturn(new LinkedHashSet<>(List.of(statements)));
        when(capturer.normalize(anyString())).thenAnswer(
                invocation -> invocation.getArgument(0, String.class));
    }

    private JsonNode plan(final String json) throws Exception {
        return mapper.readTree(json);
    }

    @Test
    void testAudit_JoinServedByInnerIndexScan_ReturnsNoViolations()
            throws Exception {
        captured(
                "select o.id from orders o join customers c on o.customer_id = c.id");
        doReturn(
                plan("""
                        {"Node Type":"Nested Loop","Plans":[
                           {"Node Type":"Seq Scan","Parent Relationship":"Outer","Relation Name":"orders"},
                           {"Node Type":"Index Scan","Parent Relationship":"Inner","Relation Name":"customers"}]}"""))
                .when(explainer).planWith(anyString(), any(String[].class));

        assertThat(loopAudit.audit(Set.of(), List.of())).isEmpty();
    }

    @Test
    void testAudit_UnindexedJoinKey_ReportsFindingAndStatement()
            throws Exception {
        captured(
                "select o.id from orders o join customers c on o.customer_id = c.id");
        doReturn(
                plan("""
                        {"Node Type":"Nested Loop","Join Filter":"(o.customer_id = c.id)","Plans":[
                           {"Node Type":"Seq Scan","Parent Relationship":"Outer","Relation Name":"orders"},
                           {"Node Type":"Seq Scan","Parent Relationship":"Inner","Relation Name":"customers"}]}"""))
                .when(explainer).planWith(anyString(), any(String[].class));

        assertThat(loopAudit.audit(Set.of(), List.of()))
                .anySatisfy(violation -> assertThat(violation.description()).contains(
                        "Nested Loop with inner Seq Scan on 'customers' joining (o.customer_id = c.id)")
                        .contains(
                                "select o.id from orders o join customers c on o.customer_id = c.id"));
    }

    @Test
    void testAudit_Planning_PenalizesSeqScanAndHashAndMergeJoins()
            throws Exception {
        captured(
                "select o.id from orders o join customers c on o.customer_id = c.id");
        doReturn(plan("""
                {"Node Type":"Nested Loop"}""")).when(explainer)
                .planWith(anyString(), any(String[].class));

        assertThat(loopAudit.audit(Set.of(), List.of())).isEmpty();

        Mockito.verify(explainer).planWith(eq(
                "select o.id from orders o join customers c on o.customer_id = c.id"),
                eq("enable_seqscan = off"), eq("enable_hashjoin = off"),
                eq("enable_mergejoin = off"));
    }

    @Test
    void testAudit_NonJoinStatementCaptured_SkipsIt() {
        captured("select * from orders where id = ?");

        assertThat(loopAudit.audit(Set.of(), List.of())).isEmpty();

        Mockito.verify(explainer, Mockito.never()).planWith(anyString(),
                any(String[].class));
    }
}
