package io.github.databaseaudits.audit.runtime.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.databaseaudits.capture.SqlCapturingStatementInspector;
import io.github.databaseaudits.plan.QueryPlanExplainer;

/**
 * Advisory: every {@code ORDER BY} the application runs should be servable by
 * an index, not by an explicit sort.
 *
 * <p>
 * Under {@code SET enable_sort = off} (the loop lives in
 * {@link CapturedSqlPlanAuditTemplate}), a {@code Sort} /
 * {@code Incremental Sort} that survives means no index (or only a leading
 * prefix) can provide the ordering. Noisier than the WHERE audit and therefore
 * advisory — sorts over aggregates, joins, expressions, windows, or small
 * result sets are legitimate and meant to be excluded. The real target is
 * sorted pagination over large tables ({@code ORDER BY … LIMIT}). Needs
 * PostgreSQL 16+ and a populated {@link SqlCapturingStatementInspector}.
 *
 * <p>
 * Exclude via the {@code audit(excludedRelations, excludedSqlFragments)}
 * arguments, or {@code @Disabled} the paired {@code @Test} to keep it as a
 * periodic report.
 *
 * <p>
 * Fix: add an index matching the {@code ORDER BY} columns (including ASC/DESC
 * and NULLS order), or exclude the relation / SQL fragment.
 */
public class OrderByIndexAudit extends CapturedSqlPlanAuditTemplate {
    /**
     * Creates the audit.
     *
     * @param queryPlanExplainer
     *                               Obtains the PostgreSQL query plan for a
     *                               captured statement.
     * @param sqlCapturer
     *                               Supplies the captured SQL to audit.
     */
    public OrderByIndexAudit(final QueryPlanExplainer queryPlanExplainer,
            final SqlCapturingStatementInspector sqlCapturer) {
        super(queryPlanExplainer, sqlCapturer);
    }

    @Override
    protected boolean isCandidate(final String upperCasedSql) {
        final boolean isOrderable = upperCasedSql.startsWith("SELECT")
                || upperCasedSql.startsWith("WITH");
        return isOrderable && upperCasedSql.contains(" ORDER BY ");
    }

    @Override
    protected String[] plannerSettings() {
        return new String[] { "enable_sort = off" };
    }

    @Override
    protected void collectFindings(final JsonNode node,
            final List<String> findings, final Set<String> excludedRelations) {
        if (node == null) {
            return;
        }
        addUnindexedSort(node, findings, excludedRelations);
        collectChildFindings(node, findings, excludedRelations);
    }

    private void addUnindexedSort(final JsonNode node,
            final List<String> findings, final Set<String> excludedRelations) {
        final String type = queryPlanExplainer.textOf(node, "Node Type");
        if ("Sort".equals(type) || "Incremental Sort".equals(type)) {
            final String relation = firstRelationName(node);
            if (relation == null || !excludedRelations.contains(relation)) {
                final String onRelation =
                        relation == null ? "" : " under '" + relation + "'";
                findings.add(type + onRelation + " by " + sortKeyOf(node));
            }
        }
    }

    private String sortKeyOf(final JsonNode sortNode) {
        final JsonNode key = sortNode.get("Sort Key");
        if (key == null || !key.isArray()) {
            return "(unknown key)";
        }
        final var parts = new ArrayList<String>();
        key.forEach(k -> parts.add(k.asText()));
        return String.join(", ", parts);
    }

    @Override
    protected String statementNoun() {
        return "ORDER BY";
    }
}
