package io.github.databaseaudits.audit.runtime.plan;

import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.databaseaudits.capture.SqlCapturingStatementInspector;
import io.github.databaseaudits.plan.QueryPlanExplainer;

/**
 * Advisory: every join the application runs should be servable through an index
 * on the join key of at least one side.
 *
 * <p>
 * Under {@code SET enable_hashjoin = off}, {@code enable_mergejoin = off}, and
 * {@code enable_seqscan = off} (the loop lives in
 * {@link CapturedSqlPlanAuditTemplate}), the planner is steered toward the plan
 * shape an indexed join key makes possible: a {@code Nested Loop} with an inner
 * {@code Index Scan}. A {@code Hash Join} or {@code Merge Join} that survives
 * the penalty — or a {@code Nested Loop} falling back to an inner
 * {@code Seq Scan} — proves no index can serve the join key. The
 * {@code WHERE}-clause audit cannot catch this: join conditions surface as
 * {@code Hash Cond}/{@code Merge Cond}/{@code Join Filter}, never as the
 * {@code Filter} it flags.
 *
 * <p>
 * Advisory like {@link OrderByIndexAudit}: some joins legitimately have no
 * indexable side — {@code FULL OUTER JOIN}s (which cannot be nested loops),
 * joins on small static tables, joins on expressions — and are meant to be
 * excluded via the {@code audit(excludedRelations, excludedSqlFragments)}
 * arguments. Needs PostgreSQL 16+ and a populated
 * {@link SqlCapturingStatementInspector} (run after the repository workload,
 * same JVM).
 *
 * <p>
 * Fix: add an index on the joined column(s), or exclude the relation / SQL
 * fragment (FULL OUTER JOINs and small static tables are common deliberate
 * exclusions).
 */
public class JoinIndexAudit extends CapturedSqlPlanAuditTemplate {
    public JoinIndexAudit(final QueryPlanExplainer queryPlanExplainer,
            final SqlCapturingStatementInspector sqlCapturer) {
        super(queryPlanExplainer, sqlCapturer);
    }

    @Override
    protected boolean isCandidate(final String upperCasedSql) {
        final boolean isJoinable = upperCasedSql.startsWith("SELECT")
                || upperCasedSql.startsWith("WITH")
                || upperCasedSql.startsWith("UPDATE")
                || upperCasedSql.startsWith("DELETE");
        return isJoinable && upperCasedSql.contains(" JOIN ");
    }

    @Override
    protected String[] plannerSettings() {
        return new String[] { "enable_seqscan = off", "enable_hashjoin = off",
                "enable_mergejoin = off" };
    }

    @Override
    protected void collectFindings(final JsonNode node,
            final List<String> findings, final Set<String> excludedRelations) {
        if (node == null) {
            return;
        }
        addSurvivingHashOrMergeJoin(node, findings, excludedRelations);
        addNestedLoopWithInnerSeqScan(node, findings, excludedRelations);
        addChildFindings(node, findings, excludedRelations);
    }

    private void addChildFindings(final JsonNode node,
            final List<String> findings, final Set<String> excludedRelations) {
        final JsonNode planNodes = node.get("Plans");
        if (planNodes != null) {
            for (final JsonNode planNode : planNodes) {
                collectFindings(planNode, findings, excludedRelations);
            }
        }
    }

    private void addSurvivingHashOrMergeJoin(final JsonNode node,
            final List<String> findings, final Set<String> excludedRelations) {
        final String type = queryPlanExplainer.textOf(node, "Node Type");
        if ("Hash Join".equals(type) || "Merge Join".equals(type)) {
            final String relation = firstRelationName(innerChildOf(node));
            addJoinFinding(type, relation, joinConditionOf(node, null),
                    findings, excludedRelations);
        }
    }

    private void addNestedLoopWithInnerSeqScan(final JsonNode node,
            final List<String> findings, final Set<String> excludedRelations) {
        if ("Nested Loop"
                .equals(queryPlanExplainer.textOf(node, "Node Type"))) {
            final JsonNode innerScan = unwrapPassThrough(innerChildOf(node));
            if (innerScan != null && "Seq Scan".equals(
                    queryPlanExplainer.textOf(innerScan, "Node Type"))) {
                final String relation =
                        queryPlanExplainer.textOf(innerScan, "Relation Name");
                addJoinFinding("Nested Loop with inner Seq Scan", relation,
                        joinConditionOf(node, innerScan), findings,
                        excludedRelations);
            }
        }
    }

    private void addJoinFinding(final String description, final String relation,
            final String condition, final List<String> findings,
            final Set<String> excludedRelations) {
        if (relation == null || !excludedRelations.contains(relation)) {
            final String onRelation =
                    relation == null ? "" : " on '" + relation + "'";
            findings.add(description + onRelation + " joining " + condition);
        }
    }

    /** The join condition, wherever this plan shape carries it. */
    private String joinConditionOf(final JsonNode joinNode,
            final JsonNode innerScan) {
        final String hashCond =
                queryPlanExplainer.textOf(joinNode, "Hash Cond");
        if (hashCond != null) {
            return hashCond;
        }
        final String mergeCond =
                queryPlanExplainer.textOf(joinNode, "Merge Cond");
        if (mergeCond != null) {
            return mergeCond;
        }
        final String joinFilter =
                queryPlanExplainer.textOf(joinNode, "Join Filter");
        if (joinFilter != null) {
            return joinFilter;
        }
        final String innerFilter = innerScan == null ? null
                : queryPlanExplainer.textOf(innerScan, "Filter");
        return innerFilter == null ? "(join condition not shown)" : innerFilter;
    }

    /**
     * The child the planner rescans per outer row — the side an index would
     * have to serve.
     */
    private JsonNode innerChildOf(final JsonNode node) {
        final JsonNode planNodes = node.get("Plans");
        if (planNodes == null) {
            return null;
        }
        for (final JsonNode planNode : planNodes) {
            if ("Inner".equals(queryPlanExplainer.textOf(planNode,
                    "Parent Relationship"))) {
                return planNode;
            }
        }
        return null;
    }

    /**
     * Descends through single-child pass-through nodes to the actual access
     * path.
     */
    private JsonNode unwrapPassThrough(final JsonNode node) {
        JsonNode current = node;
        while (current != null && isPassThrough(
                queryPlanExplainer.textOf(current, "Node Type"))) {
            final JsonNode planNodes = current.get("Plans");
            current =
                    planNodes != null && !planNodes.isEmpty() ? planNodes.get(0)
                            : null;
        }
        return current;
    }

    private boolean isPassThrough(final String nodeType) {
        return "Hash".equals(nodeType) || "Sort".equals(nodeType)
                || "Incremental Sort".equals(nodeType)
                || "Materialize".equals(nodeType) || "Memoize".equals(nodeType);
    }

    /**
     * First {@code Relation Name} at or below this node — the relation whose
     * join key an index would serve.
     */
    private String firstRelationName(final JsonNode node) {
        if (node == null) {
            return null;
        }
        final String relation =
                queryPlanExplainer.textOf(node, "Relation Name");
        if (relation != null) {
            return relation;
        }
        final JsonNode planNodes = node.get("Plans");
        if (planNodes != null) {
            for (final JsonNode planNode : planNodes) {
                final String found = firstRelationName(planNode);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Override
    protected String statementNoun() {
        return "JOIN";
    }
}
