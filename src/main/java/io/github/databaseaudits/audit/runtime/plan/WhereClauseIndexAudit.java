package io.github.databaseaudits.audit.runtime.plan;

import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.databaseaudits.capture.SqlCapturingStatementInspector;
import io.github.databaseaudits.plan.QueryPlanExplainer;

/**
 * Verifies every column the application filters on in a {@code WHERE} clause
 * should have a usable index.
 *
 * <p>
 * Under {@code SET enable_seqscan = off} (the loop lives in
 * {@link CapturedSqlPlanAuditTemplate}), a {@code Seq Scan} that still carries
 * a {@code Filter} means no index can satisfy that predicate. It catches a
 * <em>fully</em> unindexed predicate, not a {@code Filter} on an
 * {@code Index Scan} ({@code a} indexed, {@code b} not in {@code a=? AND b=?})
 * — so it verifies every {@code WHERE} has <em>at least one</em> usable index.
 * Needs PostgreSQL 16+ and a populated {@link SqlCapturingStatementInspector}
 * (run after the repository workload, same JVM).
 *
 * <p>
 * Exclude un-indexable predicates ({@code LIKE '%x'}, {@code <> ?}) or small
 * static tables via the {@code audit(excludedRelations, excludedSqlFragments)}
 * arguments.
 *
 * <p>
 * Fix: add an index on the filtered column(s), or exclude the relation / SQL
 * fragment.
 */
public class WhereClauseIndexAudit extends CapturedSqlPlanAuditTemplate {
    /**
     * Creates the audit.
     *
     * @param queryPlanExplainer
     *                               Obtains the PostgreSQL query plan for a
     *                               captured statement.
     * @param sqlCapturer
     *                               Supplies the captured SQL to audit.
     */
    public WhereClauseIndexAudit(final QueryPlanExplainer queryPlanExplainer,
            final SqlCapturingStatementInspector sqlCapturer) {
        super(queryPlanExplainer, sqlCapturer);
    }

    @Override
    protected boolean isCandidate(final String upperCasedSql) {
        final boolean isDml = upperCasedSql.startsWith("SELECT")
                || upperCasedSql.startsWith("UPDATE")
                || upperCasedSql.startsWith("DELETE");
        return isDml && upperCasedSql.contains(" WHERE ");
    }

    @Override
    protected String[] plannerSettings() {
        return new String[] { "enable_seqscan = off" };
    }

    @Override
    protected void collectFindings(final JsonNode node,
            final List<String> findings, final Set<String> excludedRelations) {
        if (node != null) {
            addFilteredSeqScan(node, findings, excludedRelations);
            addPlansFilteredSeqScan(node, findings, excludedRelations);
        }
    }

    private void addFilteredSeqScan(final JsonNode node,
            final List<String> findings, final Set<String> excludedRelations) {
        if ("Seq Scan".equals(queryPlanExplainer.textOf(node, "Node Type"))
                && node.hasNonNull("Filter")) {
            final String relation =
                    queryPlanExplainer.textOf(node, "Relation Name");
            if (relation == null || !excludedRelations.contains(relation)) {
                findings.add("Seq Scan on '" + relation + "' filtering "
                        + queryPlanExplainer.textOf(node, "Filter"));
            }
        }
    }

    private void addPlansFilteredSeqScan(final JsonNode node,
            final List<String> findings, final Set<String> excludedRelations) {
        final JsonNode planNodes = node.get("Plans");
        if (planNodes != null) {
            for (final JsonNode planNode : planNodes) {
                collectFindings(planNode, findings, excludedRelations);
            }
        }
    }

    @Override
    protected String statementNoun() {
        return "WHERE-clause";
    }
}
