package io.github.databaseaudits.audit.runtime.plan;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.databaseaudits.capture.SqlCapturingStatementInspector;
import io.github.databaseaudits.plan.QueryPlanExplainer;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Template-method base for the EXPLAIN-driven runtime audits
 * ({@link WhereClauseIndexAudit}, {@link OrderByIndexAudit},
 * {@link JoinIndexAudit}).
 *
 * <p>
 * {@link #audit(Set, Collection)} is the fixed algorithm: read the captured
 * SQL, de-duplicate by statement shape, plan each candidate via
 * {@link QueryPlanExplainer} with the audit's planner settings, and return one
 * readable finding per offending plan node. It also owns the two guards every
 * plan audit shares — an empty capture, and a wholly-unexplainable (vacuous)
 * run — which throw {@link IllegalStateException} rather than returning no
 * findings, so a misconfigured run never looks clean. That logic lives in
 * exactly one place.
 *
 * <p>
 * Subclasses supply only the variation points: which statements to look at
 * ({@link #isCandidate(String)}), which planner GUCs to penalize
 * ({@link #plannerSettings()}), how to recognize an offending node
 * ({@link #collectFindings(JsonNode, List, Set)}), and the statement noun for
 * the vacuous-run guard ({@link #statementNoun()}). Dependencies are
 * constructor-injected and passed up via {@code super(...)}.
 */
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@Slf4j
abstract class CapturedSqlPlanAuditTemplate {
    private static final String FAIL_NO_EXPLAINS_MSG = """
            %d %s statement shape(s) were captured but none could be EXPLAINed,\
             so this audit verified nothing\
             — these plan-based audits are PostgreSQL 16+ only.
             On PostgreSQL, the most likely cause is a missing \
             preferQueryMode=simple on the test datasource JDBC URL.
             See: https://database-audits.github.io/spring-boot-integration/usage.html#postgresql-jdbc-requirement""";

    private static final String SKIP_UNCHECKABLE_MSG = """
            Un-checkable (parameter type inference, jsonb `?`, unparsable).\
             The subsequent all-skipped guard\
             still catches a wholly vacuous run.""";

    /**
     * Available to subclasses for
     * {@link QueryPlanExplainer#textOf(JsonNode, String)} while walking the
     * plan.
     */
    protected final QueryPlanExplainer queryPlanExplainer;

    private final SqlCapturingStatementInspector sqlCapturer;

    /**
     * The template method — fixed across audits. Returns one finding per
     * offending plan node; an empty list when every candidate statement is
     * served by an index.
     *
     * @param excludedRelations
     *                                 The table/relation names to skip.
     * @param excludedSqlFragments
     *                                 The SQL fragments whose containing
     *                                 statements to skip.
     * @return One finding per offending plan node; an empty list when every
     *         candidate statement is served by an index.
     * @throws UnsupportedOperationException
     *                                           On any non-PostgreSQL platform.
     * @throws IllegalStateException
     *                                           If nothing was captured, or if
     *                                           statements were captured but
     *                                           none could be EXPLAINed.
     */
    public final List<String> audit(final Set<String> excludedRelations,
            final Collection<String> excludedSqlFragments) {
        queryPlanExplainer.requirePlanAuditSupport(getClass().getSimpleName());

        final Set<String> capturedSql = sqlCapturer.capturedSql();
        if (capturedSql.isEmpty()) {
            throw new IllegalStateException(
                    SqlCapturingStatementInspector.EMPTY_CAPTURE_MESSAGE);
        }

        final var violations = new TreeMap<String, String>();
        final var checkedShapes = new HashSet<String>();

        final int explainedCount =
                collectViolations(capturedSql, excludedRelations,
                        excludedSqlFragments, violations, checkedShapes);

        requireSomethingExplained(checkedShapes, explainedCount);

        final int skippedCount = checkedShapes.size() - explainedCount;
        log.debug("audit: counts: captured={},"
                + " checked={}, explained={}, skipped={}, violations={}",
                capturedSql.size(), checkedShapes.size(), explainedCount,
                skippedCount, violations.size());

        return findingsOf(violations);
    }

    private int collectViolations(final Set<String> capturedSql,
            final Set<String> excludedRelations,
            final Collection<String> excludedSqlFragments,
            final TreeMap<String, String> violations,
            final HashSet<String> checkedShapes) {
        int explainedCount = 0;
        for (final String rawSql : capturedSql) {
            final String trimmedSql = rawSql.strip();
            final String normalizedSql = sqlCapturer.normalize(trimmedSql);
            final String upperCasedSql = normalizedSql.toUpperCase();

            if (!isCandidate(upperCasedSql)
                    || isExcluded(normalizedSql, excludedSqlFragments)
                    || !checkedShapes.add(upperCasedSql)) {
                continue;
            }
            explainedCount +=
                    explain(trimmedSql, excludedRelations, violations);
        }
        return explainedCount;
    }

    private int explain(final String sql, final Set<String> excludedRelations,
            final TreeMap<String, String> violations) {
        try {
            final JsonNode plan =
                    queryPlanExplainer.planWith(sql, plannerSettings());
            final List<String> findings = new ArrayList<>();
            collectFindings(plan, findings, excludedRelations);
            if (!findings.isEmpty()) {
                violations.put(sql, String.join("; ", findings));
            }
            return 1;
        } catch (final Exception e) {
            log.debug("Skipping un-explainable statement [{}]: {}", sql,
                    SKIP_UNCHECKABLE_MSG, e);
            return 0;
        }
    }

    private boolean isExcluded(final String normalizedSql,
            final Collection<String> excludedSqlFragments) {
        final String lower = normalizedSql.toLowerCase();
        return excludedSqlFragments.stream()
                .anyMatch(f -> lower.contains(f.toLowerCase()));
    }

    private void requireSomethingExplained(final HashSet<String> checkedShapes,
            final int explainedCount) {
        if (!checkedShapes.isEmpty() && explainedCount == 0) {
            throw new IllegalStateException(FAIL_NO_EXPLAINS_MSG
                    .formatted(checkedShapes.size(), statementNoun()));
        }
    }

    private List<String> findingsOf(final TreeMap<String, String> violations) {
        return violations.entrySet().stream()
                .map(violation -> violation.getValue() + "\n      "
                        + violation.getKey())
                .toList();
    }

    /**
     * Whether this normalized, upper-cased statement is one this audit should
     * EXPLAIN.
     *
     * @param upperCasedSql The normalized, upper-cased statement text.
     * @return {@code true} if this audit should EXPLAIN the statement.
     */
    protected abstract boolean isCandidate(String upperCasedSql);

    /**
     * Planner GUCs to penalize so a surviving node proves a missing index, e.g.
     * {@code "enable_seqscan = off"}.
     *
     * @return The planner GUCs to penalize, each as a {@code SET} argument.
     */
    protected abstract String[] plannerSettings();

    /**
     * Walk the plan tree from {@code plan} and add a human-readable finding for
     * each offending node.
     *
     * @param plan The plan node to walk.
     * @param findings The list to add human-readable findings to.
     * @param excludedRelations The relation names to skip.
     */
    protected abstract void collectFindings(JsonNode plan,
            List<String> findings, Set<String> excludedRelations);

    /**
     * Noun for the vacuous-run guard message, e.g. {@code "WHERE-clause"} or
     * {@code "ORDER BY"}.
     *
     * @return The statement noun for the vacuous-run guard message.
     */
    protected abstract String statementNoun();
}
