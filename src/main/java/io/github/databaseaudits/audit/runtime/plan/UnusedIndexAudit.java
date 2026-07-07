package io.github.databaseaudits.audit.runtime.plan;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import org.jspecify.annotations.Nullable;

import io.github.databaseaudits.audit.finding.Finding;
import io.github.databaseaudits.audit.finding.UnusedIndexFinding;
import io.github.databaseaudits.capture.SqlCapturingStatementInspector;
import io.github.databaseaudits.catalog.ForeignKeyCatalog;
import io.github.databaseaudits.catalog.ForeignKeyDefinition;
import io.github.databaseaudits.catalog.IndexCatalog;
import io.github.databaseaudits.catalog.IndexDefinition;
import io.github.databaseaudits.plan.QueryPlanExplainer;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Advisory: every index should be used by at least one captured statement's
 * plan.
 *
 * <p>
 * Every index taxes every write and consumes cache/storage; one that no real
 * query uses is pure cost. This is the <em>inverse</em> of the other plan
 * audits — instead of proving one statement lacks a serving index, it proves
 * one <em>index</em> serves no statement in the whole captured workload — so
 * it does not extend {@link CapturedSqlPlanAuditTemplate} (whose fixed
 * algorithm emits one finding per offending statement; this audit needs the
 * union of index usage across every statement, then a diff against the
 * catalog). Candidates are planned via the natural generic plan (no planner
 * penalties): {@link QueryPlanExplainer#planWith(String, String...)} with no
 * session settings, walking every {@code Index Name} the plan mentions at any
 * depth. An index from {@link IndexCatalog} is <em>justified</em> — never
 * reported — when it backs a primary key or a unique constraint (a partial
 * index is also never reported: a generic plan without bind values usually
 * cannot prove a partial index unusable, so this is a conservative skip), when
 * its name appears in the collected usage, or when it covers a foreign key
 * (an index {@link io.github.databaseaudits.audit.catalog.ForeignKeyIndexAudit
 * ForeignKeyIndexAudit} demands must never be reported unused here).
 *
 * <p>
 * <strong>This audit is advisory and workload-dependent</strong>: the capture
 * must hold a representative workload, or a genuinely used index looks
 * unused. A generic plan also has no real table statistics, so it can miss an
 * index the planner would pick under production data's distribution. Always
 * confirm against production {@code pg_stat_user_indexes} before dropping an
 * index this audit reports. Requires PostgreSQL 16+ and
 * {@code preferQueryMode=simple} on the JDBC URL, exactly like the other plan
 * audits; fails fast via {@link QueryPlanExplainer#requirePlanAuditSupport(String)}
 * on any other platform, and throws rather than reporting nothing (or, worse,
 * reporting every non-justified index as unused with no evidence at all) on an
 * empty capture, a capture with no {@code SELECT}/{@code WITH}/{@code UPDATE}/
 * {@code DELETE} candidates at all (e.g. an INSERT-only workload), or a
 * wholly-unexplainable run.
 *
 * <p>
 * Fix: drop the index after confirming against production usage statistics,
 * or exclude it (e.g. an index kept for a rare admin query outside the
 * captured workload).
 */
@AllArgsConstructor
@Slf4j
public class UnusedIndexAudit {
    private static final String FAIL_NO_EXPLAINS_MSG = """
            %d candidate statement shape(s) were captured but none could be EXPLAINed,\
             so this audit verified nothing\
             — this plan-based audit is PostgreSQL 16+ only.
             On PostgreSQL, the most likely cause is a missing \
            preferQueryMode=simple on the test datasource JDBC URL.
             See: https://database-audits.github.io/spring-boot-integration/usage.html#postgresql-jdbc-requirement""";

    private static final String FAIL_NO_CANDIDATES_MSG = """
            %d statement(s) were captured but none were SELECT/WITH/UPDATE/DELETE,\
             so this audit verified nothing about index usage\
             — every catalog index would otherwise look unused with no evidence at all.
             Capture a representative read/write workload (not just INSERTs) before running this audit.""";

    private final QueryPlanExplainer queryPlanExplainer;
    private final SqlCapturingStatementInspector sqlCapturer;
    private final IndexCatalog indexCatalog;
    private final ForeignKeyCatalog foreignKeyCatalog;

    /**
     * Returns one {@link Finding} for every index used by no captured
     * statement's plan, except the excluded ones; an empty list when every
     * index is justified.
     *
     * @param schema
     *                            The schema to scan.
     * @param excludedIndexes
     *                            The index names to skip.
     * @return One {@link Finding} per unused index, sorted by table then index
     *         — its {@link Finding#description() description} is the reported
     *         line; an empty list when every index is justified.
     * @throws UnsupportedOperationException
     *                                           On any non-PostgreSQL
     *                                           platform.
     * @throws IllegalStateException
     *                                           If nothing was captured, if
     *                                           nothing captured was a
     *                                           SELECT/WITH/UPDATE/DELETE
     *                                           candidate, or if candidates
     *                                           were captured but none could
     *                                           be EXPLAINed.
     */
    public List<Finding> audit(final String schema,
            final Set<String> excludedIndexes) {
        queryPlanExplainer.requirePlanAuditSupport("UnusedIndexAudit");

        final Set<String> capturedSql = sqlCapturer.capturedSql();
        if (capturedSql.isEmpty()) {
            throw new IllegalStateException(
                    SqlCapturingStatementInspector.EMPTY_CAPTURE_MESSAGE);
        }

        final Set<String> usedIndexNames = new HashSet<>();
        final Set<String> checkedShapes = new HashSet<>();
        int explainedCount = 0;
        for (final String rawSql : capturedSql) {
            final String trimmedSql = rawSql.strip();
            final String normalizedSql = sqlCapturer.normalize(trimmedSql);
            final String upperCasedSql =
                    normalizedSql.toUpperCase(Locale.ROOT);
            if (!isCandidate(upperCasedSql)
                    || !checkedShapes.add(upperCasedSql)) {
                continue;
            }
            explainedCount += explain(trimmedSql, usedIndexNames);
        }

        if (checkedShapes.isEmpty()) {
            throw new IllegalStateException(
                    FAIL_NO_CANDIDATES_MSG.formatted(capturedSql.size()));
        }
        if (explainedCount == 0) {
            throw new IllegalStateException(
                    FAIL_NO_EXPLAINS_MSG.formatted(checkedShapes.size()));
        }

        final List<ForeignKeyDefinition> foreignKeys =
                foreignKeyCatalog.readAll(schema);

        return indexCatalog.readAll(schema).stream()
                .filter(index -> !isJustified(index, usedIndexNames,
                        foreignKeys))
                .filter(index -> !excludedIndexes.contains(index.indexName()))
                .sorted(Comparator.comparing(IndexDefinition::tableName)
                        .thenComparing(IndexDefinition::indexName))
                .<Finding>map(index -> new UnusedIndexFinding(
                        index.tableName(), index.indexName()))
                .toList();
    }

    private boolean isCandidate(final String upperCasedSql) {
        return upperCasedSql.startsWith("SELECT")
                || upperCasedSql.startsWith("WITH")
                || upperCasedSql.startsWith("UPDATE")
                || upperCasedSql.startsWith("DELETE");
    }

    private int explain(final String sql, final Set<String> usedIndexNames) {
        try {
            final JsonNode plan = queryPlanExplainer.planWith(sql);
            collectIndexNames(plan, usedIndexNames);
            return 1;
        } catch (final Exception e) {
            log.debug(
                    "Skipping un-explainable statement [{}]: un-checkable (parameter "
                            + "type inference, jsonb `?`, unparsable). The subsequent "
                            + "all-skipped guard still catches a wholly vacuous run.",
                    sql, e);
            return 0;
        }
    }

    private void collectIndexNames(final @Nullable JsonNode node,
            final Set<String> usedIndexNames) {
        if (node == null) {
            return;
        }
        final String indexName =
                queryPlanExplainer.textOf(node, PlanJson.INDEX_NAME);
        if (indexName != null) {
            usedIndexNames.add(indexName);
        }
        final JsonNode planNodes = node.get(PlanJson.PLANS);
        if (planNodes != null) {
            for (final JsonNode planNode : planNodes) {
                collectIndexNames(planNode, usedIndexNames);
            }
        }
    }

    private boolean isJustified(final IndexDefinition index,
            final Set<String> usedIndexNames,
            final List<ForeignKeyDefinition> foreignKeys) {
        return index.primary() || index.unique() || index.partial()
                || usedIndexNames.contains(index.indexName())
                || coversAnyForeignKey(index, foreignKeys);
    }

    private boolean coversAnyForeignKey(final IndexDefinition index,
            final List<ForeignKeyDefinition> foreignKeys) {
        return foreignKeys.stream()
                .filter(fk -> fk.tableName().equals(index.tableName()))
                .anyMatch(fk -> index.leadingColumnsCover(fk.columns()));
    }
}
