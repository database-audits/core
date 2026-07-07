package io.github.databaseaudits.audit.runtime.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.databaseaudits.audit.finding.Finding;
import io.github.databaseaudits.capture.SqlCapturingStatementInspector;
import io.github.databaseaudits.catalog.ForeignKeyCatalog;
import io.github.databaseaudits.catalog.ForeignKeyDefinition;
import io.github.databaseaudits.catalog.IndexCatalog;
import io.github.databaseaudits.catalog.IndexDefinition;
import io.github.databaseaudits.plan.QueryPlanExplainer;
import io.github.databaseaudits.platform.DatabasePlatform;

/**
 * Pure unit tests of the audit's index-usage/justification logic with a
 * mocked capturer, a spied explainer whose {@code planWith} is stubbed (no
 * I/O; the real {@code textOf}/{@code requirePlanAuditSupport} pure helpers
 * run), and mocked {@link IndexCatalog}/{@link ForeignKeyCatalog}, following
 * {@link WhereClauseIndexAuditTest}'s fixture-plan style. The end-to-end path
 * against a real PostgreSQL container is covered by
 * {@link PlanAuditsPostgreSqlIT}.
 */
class UnusedIndexAuditTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SqlCapturingStatementInspector capturer =
            mock(SqlCapturingStatementInspector.class);
    private final QueryPlanExplainer explainer =
            spy(new QueryPlanExplainer(null, DatabasePlatform.POSTGRESQL));
    private final IndexCatalog indexCatalog = mock(IndexCatalog.class);
    private final ForeignKeyCatalog foreignKeyCatalog =
            mock(ForeignKeyCatalog.class);
    private final UnusedIndexAudit audit = new UnusedIndexAudit(explainer,
            capturer, indexCatalog, foreignKeyCatalog);

    private void captured(final String... statements) {
        when(capturer.capturedSql())
                .thenReturn(new LinkedHashSet<>(List.of(statements)));
        when(capturer.normalize(anyString())).thenAnswer(
                invocation -> invocation.getArgument(0, String.class));
    }

    private JsonNode plan(final String json) throws Exception {
        return mapper.readTree(json);
    }

    private static IndexDefinition index(final String table,
            final String name, final boolean unique, final boolean primary,
            final boolean partial, final String... columns) {
        return new IndexDefinition(table, name, unique, primary, partial,
                List.of(columns));
    }

    @Test
    void testAudit_IndexNamedInPlan_NotReported() throws Exception {
        captured("select * from t where a = ?");
        doReturn(plan("""
                {"Node Type":"Index Scan","Index Name":"idx_t_a"}"""))
                .when(explainer).planWith(anyString(), any(String[].class));
        when(indexCatalog.readAll("public")).thenReturn(List.of(
                index("t", "idx_t_a", false, false, false, "a")));
        when(foreignKeyCatalog.readAll("public")).thenReturn(List.of());

        assertThat(audit.audit("public", Set.of()))
                .as("An index named in the plan should not be reported as unused.")
                .isEmpty();
    }

    @Test
    void testAudit_IndexNamedInNestedPlanNode_NotReported() throws Exception {
        captured("select * from t where a = ?");
        doReturn(plan(
                """
                        {"Node Type":"Limit","Plans":[
                           {"Node Type":"Index Scan","Index Name":"idx_t_a"}]}"""))
                .when(explainer).planWith(anyString(), any(String[].class));
        when(indexCatalog.readAll("public")).thenReturn(List.of(
                index("t", "idx_t_a", false, false, false, "a")));
        when(foreignKeyCatalog.readAll("public")).thenReturn(List.of());

        assertThat(audit.audit("public", Set.of()))
                .as("An index named in a plan node nested under Plans should still count as used.")
                .isEmpty();
    }

    @Test
    void testAudit_CatalogIndexNeverNamed_Reported() throws Exception {
        captured("select * from t where a = ?");
        doReturn(plan("""
                {"Node Type":"Seq Scan","Relation Name":"t"}""")).when(explainer)
                .planWith(anyString(), any(String[].class));
        when(indexCatalog.readAll("public")).thenReturn(List.of(
                index("t", "idx_t_unused", false, false, false, "b")));
        when(foreignKeyCatalog.readAll("public")).thenReturn(List.of());

        assertThat(audit.audit("public", Set.of()))
                .extracting(Finding::description)
                .as("A catalog index never named in any plan should be reported unused.")
                .containsExactly(
                        "t.idx_t_unused is used by no captured statement's plan");
    }

    @Test
    void testAudit_PrimaryUniqueAndPartialIndexes_NeverReported()
            throws Exception {
        captured("select * from t where a = ?");
        doReturn(plan("""
                {"Node Type":"Seq Scan","Relation Name":"t"}""")).when(explainer)
                .planWith(anyString(), any(String[].class));
        when(indexCatalog.readAll("public")).thenReturn(List.of(
                index("t", "t_pkey", true, true, false, "id"),
                index("t", "uq_t_code", true, false, false, "code"),
                index("t", "idx_t_partial", false, false, true, "flag")));
        when(foreignKeyCatalog.readAll("public")).thenReturn(List.of());

        assertThat(audit.audit("public", Set.of()))
                .as("Primary, unique, and partial indexes should never be reported.")
                .isEmpty();
    }

    @Test
    void testAudit_IndexCoveringForeignKey_NotReported() throws Exception {
        captured("select * from t where a = ?");
        doReturn(plan("""
                {"Node Type":"Seq Scan","Relation Name":"t"}""")).when(explainer)
                .planWith(anyString(), any(String[].class));
        when(indexCatalog.readAll("public")).thenReturn(List.of(index("child",
                "idx_child_fk", false, false, false, "parent_id",
                "created_at")));
        when(foreignKeyCatalog.readAll("public"))
                .thenReturn(List.of(new ForeignKeyDefinition("child",
                        "fk_child_parent", "parent", List.of("parent_id"),
                        List.of("id"))));

        assertThat(audit.audit("public", Set.of()))
                .as("An index whose leading columns cover a foreign key should never be reported, even multi-column.")
                .isEmpty();
    }

    @Test
    void testAudit_ExcludedIndexName_NotReported() throws Exception {
        captured("select * from t where a = ?");
        doReturn(plan("""
                {"Node Type":"Seq Scan","Relation Name":"t"}""")).when(explainer)
                .planWith(anyString(), any(String[].class));
        when(indexCatalog.readAll("public")).thenReturn(List.of(
                index("t", "idx_t_unused", false, false, false, "b")));
        when(foreignKeyCatalog.readAll("public")).thenReturn(List.of());

        assertThat(audit.audit("public", Set.of("idx_t_unused")))
                .as("Excluding the index by name should suppress the finding.")
                .isEmpty();
    }

    @Test
    void testAudit_EmptyCapture_ThrowsRatherThanReportingVacuously() {
        captured();

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> audit.audit("public", Set.of()))
                .withMessageContaining("No SQL was captured");
    }

    @Test
    void testAudit_NoCandidateStatements_ThrowsRatherThanReportingEveryIndexUnused() {
        captured("insert into t (a) values (?)");
        when(indexCatalog.readAll("public")).thenReturn(List.of(
                index("t", "idx_t_a", false, false, false, "a")));

        assertThatExceptionOfType(IllegalStateException.class)
                .as("A capture with no SELECT/WITH/UPDATE/DELETE candidates (e.g. INSERT-only) "
                        + "must throw rather than report every non-justified index as unused with no evidence.")
                .isThrownBy(() -> audit.audit("public", Set.of()))
                .withMessageContaining("none were SELECT/WITH/UPDATE/DELETE");
    }

    @Test
    void testAudit_AllCandidatesUnexplainable_ThrowsMentioningPreferQueryModeSimple() {
        captured("select * from t where a = ?");
        doThrow(new IllegalStateException("Could not EXPLAIN")).when(explainer)
                .planWith(anyString(), any(String[].class));

        assertThatExceptionOfType(IllegalStateException.class)
                .as("An unexplainable capture should throw mentioning the required DataSource setting.")
                .isThrownBy(() -> audit.audit("public", Set.of()))
                .withMessageContaining("preferQueryMode=simple");
    }

    @Test
    void testAudit_NonPostgreSqlPlatform_ThrowsBeforeAnyCaptureCheck() {
        final QueryPlanExplainer mysqlExplainer =
                new QueryPlanExplainer(null, DatabasePlatform.MYSQL);
        final UnusedIndexAudit mysqlAudit = new UnusedIndexAudit(
                mysqlExplainer, capturer, indexCatalog, foreignKeyCatalog);

        assertThatExceptionOfType(UnsupportedOperationException.class)
                .as("A non-PostgreSQL platform should be rejected before any capture is examined.")
                .isThrownBy(() -> mysqlAudit.audit("public", Set.of()))
                .withMessageContaining("UnusedIndexAudit")
                .withMessageContaining("MYSQL");
    }
}
