package io.github.databaseaudits.audit.runtime.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
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
 * {@link WhereClauseIndexAuditIT}.
 */
class WhereClauseIndexAuditTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final WhereClauseIndexAudit audit = new WhereClauseIndexAudit(
            new QueryPlanExplainer(null, DatabasePlatform.POSTGRESQL),
            mock(SqlCapturingStatementInspector.class));

    @Test
    void testIsCandidate_DmlWithWhere_AcceptedOthersRejected() {
        assertThat(audit.isCandidate("SELECT * FROM T WHERE A = $1")).isTrue();
        assertThat(audit.isCandidate("UPDATE T SET A = $1 WHERE B = $2"))
                .isTrue();
        assertThat(audit.isCandidate("DELETE FROM T WHERE A = $1")).isTrue();
        assertThat(audit.isCandidate("SELECT * FROM T")).isFalse();
        assertThat(audit.isCandidate("INSERT INTO T VALUES ($1)")).isFalse();
    }

    @Test
    void testCollectFindings_SeqScanWithFilter_Flags() throws Exception {
        final JsonNode plan = mapper.readTree(
                """
                        {"Node Type":"Seq Scan","Relation Name":"orders","Filter":"(customer_id = $1)"}""");
        final List<String> findings = new ArrayList<>();
        audit.collectFindings(plan, findings, Set.of());
        assertThat(findings).containsExactly(
                "Seq Scan on 'orders' filtering (customer_id = $1)");
    }

    @Test
    void testCollectFindings_FilteredSeqScanWithoutRelationName_FlagsWithNullRelation()
            throws Exception {
        final JsonNode plan = mapper.readTree("""
                {"Node Type":"Seq Scan","Filter":"(x = $1)"}""");
        final List<String> findings = new ArrayList<>();
        audit.collectFindings(plan, findings, Set.of());
        assertThat(findings)
                .containsExactly("Seq Scan on 'null' filtering (x = $1)");
    }

    @Test
    void testCollectFindings_SeqScanWithoutFilterOrFilteredIndexScan_Ignored()
            throws Exception {
        final JsonNode seqNoFilter = mapper.readTree("""
                {"Node Type":"Seq Scan","Relation Name":"orders"}""");
        final JsonNode indexScan = mapper.readTree(
                """
                        {"Node Type":"Index Scan","Relation Name":"orders","Filter":"(b = $1)"}""");
        final List<String> findings = new ArrayList<>();
        audit.collectFindings(seqNoFilter, findings, Set.of());
        audit.collectFindings(indexScan, findings, Set.of());
        assertThat(findings).isEmpty();
    }

    @Test
    void testCollectFindings_ExcludedRelation_NotFlagged() throws Exception {
        final JsonNode plan = mapper.readTree(
                """
                        {"Node Type":"Seq Scan","Relation Name":"country","Filter":"(code = $1)"}""");
        final List<String> findings = new ArrayList<>();
        audit.collectFindings(plan, findings, Set.of("country"));
        assertThat(findings).isEmpty();
    }

    @Test
    void testCollectFindings_ChildPlans_RecursesIntoThem() throws Exception {
        final JsonNode plan = mapper.readTree(
                """
                        {"Node Type":"Limit","Plans":[
                           {"Node Type":"Seq Scan","Relation Name":"orders","Filter":"(x = $1)"}]}""");
        final List<String> findings = new ArrayList<>();
        audit.collectFindings(plan, findings, Set.of());
        assertThat(findings)
                .containsExactly("Seq Scan on 'orders' filtering (x = $1)");
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
    private final WhereClauseIndexAudit loopAudit =
            new WhereClauseIndexAudit(explainer, capturer);

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
    void testAudit_EveryPlanServedByIndex_ReturnsNoViolations()
            throws Exception {
        captured("select * from t where a = ?");
        doReturn(plan("""
                {"Node Type":"Index Scan","Relation Name":"t"}"""))
                .when(explainer).planWith(anyString(), any(String[].class));

        assertThat(loopAudit.audit(Set.of(), List.of())).isEmpty();
    }

    @Test
    void testAudit_UnindexedSeqScan_ReportsFindingAndStatement()
            throws Exception {
        captured("select * from t where a = ?");
        doReturn(
                plan("""
                        {"Node Type":"Seq Scan","Relation Name":"t","Filter":"(a = $1)"}"""))
                .when(explainer).planWith(anyString(), any(String[].class));

        assertThat(loopAudit.audit(Set.of(), List.of()))
                .anySatisfy(violation -> assertThat(violation)
                        .contains("Seq Scan on 't' filtering (a = $1)")
                        .contains("select * from t where a = ?"));
    }

    @Test
    void testAudit_Planning_PenalizesSeqScan() throws Exception {
        captured("select * from t where a = ?");
        doReturn(plan("""
                {"Node Type":"Index Scan"}""")).when(explainer)
                .planWith(anyString(), any(String[].class));

        assertThat(loopAudit.audit(Set.of(), List.of())).isEmpty();

        Mockito.verify(explainer).planWith(eq("select * from t where a = ?"),
                eq("enable_seqscan = off"));
    }

    @Test
    void testAudit_DuplicateStatementShapes_ExplainsEachShapeOnce()
            throws Exception {
        captured("select * from t where a = ?", "SELECT * FROM T WHERE A = ?");
        doReturn(plan("""
                {"Node Type":"Index Scan"}""")).when(explainer)
                .planWith(anyString(), any(String[].class));

        assertThat(loopAudit.audit(Set.of(), List.of())).isEmpty();

        Mockito.verify(explainer, times(1)).planWith(anyString(),
                any(String[].class));
    }

    @Test
    void testAudit_NonCandidateStatementCaptured_SkipsItAndExplainsOnlyCandidates()
            throws Exception {
        captured("insert into t values (?)", "select * from t where a = ?");
        doReturn(plan("""
                {"Node Type":"Index Scan"}""")).when(explainer)
                .planWith(anyString(), any(String[].class));

        assertThat(loopAudit.audit(Set.of(), List.of())).isEmpty();

        Mockito.verify(explainer).planWith(eq("select * from t where a = ?"),
                any(String[].class));
        Mockito.verify(explainer, Mockito.never())
                .planWith(eq("insert into t values (?)"), any(String[].class));
    }

    @Test
    void testAudit_ExcludedSqlFragment_SkipsStatementEntirely() {
        captured("select * from t where a like ?");

        assertThat(loopAudit.audit(Set.of(), List.of("like ?"))).isEmpty();

        Mockito.verify(explainer, Mockito.never()).planWith(anyString(),
                any(String[].class));
    }

    @Test
    void testAudit_UnexplainableStatement_SkipsItAndChecksRest()
            throws Exception {
        captured("select * from broken where a ?? ?",
                "select * from t where a = ?");
        doThrow(new IllegalStateException("Could not EXPLAIN")).when(explainer)
                .planWith(eq("select * from broken where a ?? ?"),
                        any(String[].class));
        doReturn(plan("""
                {"Node Type":"Index Scan"}""")).when(explainer).planWith(
                eq("select * from t where a = ?"), any(String[].class));

        assertThat(loopAudit.audit(Set.of(), List.of())).isEmpty();
    }
}
