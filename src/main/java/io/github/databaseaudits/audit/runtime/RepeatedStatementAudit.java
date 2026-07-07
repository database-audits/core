package io.github.databaseaudits.audit.runtime;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import io.github.databaseaudits.audit.finding.Finding;
import io.github.databaseaudits.audit.finding.RepeatedStatementFinding;
import io.github.databaseaudits.capture.SqlCapturingStatementInspector;
import lombok.AllArgsConstructor;

/**
 * Advisory: no captured {@code SELECT} (or {@code WITH} CTE query) statement
 * shape should run at least as many times as the given threshold — the
 * signature of an N+1 statement burst (one parent query followed by one
 * identical-shaped child {@code SELECT} per row).
 *
 * <p>
 * Reads {@link SqlCapturingStatementInspector#executionCounts()}, aggregating
 * raw statement variants that normalize identically, and reports every
 * {@code SELECT}/{@code WITH} shape captured at least {@code threshold}
 * times. Only reads are in scope: a repeated write is more often batching or
 * test fixture churn than an N+1 burst. Counts accumulate for the capturer's whole
 * lifetime, so across a long-running suite legitimate re-execution can
 * inflate them — for a sharp signal, call
 * {@link SqlCapturingStatementInspector#clear()} before a representative
 * workload and audit right after, and choose a threshold above the workload's
 * largest expected collection size. A typical wiring uses a generous
 * threshold (for example 50) as a regression tripwire rather than a precise
 * count. Throws on an empty capture rather than reporting nothing vacuously.
 * Pass legitimately hot statements' SQL fragments (matched case-insensitively)
 * as {@code excludedSqlFragments}.
 *
 * <p>
 * Fix: eliminate the N+1 with a fetch join, {@code @EntityGraph}, or
 * {@code @BatchSize}/{@code hibernate.default_batch_fetch_size}, or exclude
 * the statement if the repetition is deliberate.
 */
@AllArgsConstructor
public class RepeatedStatementAudit {
    private final SqlCapturingStatementInspector sqlCapturer;

    /**
     * Returns one {@link Finding} for every distinct {@code SELECT}/
     * {@code WITH} shape captured at least {@code threshold} times, except
     * statements matching an excluded fragment; an empty list when none
     * reaches the threshold.
     *
     * @param threshold
     *                                 The minimum capture count (inclusive)
     *                                 for a statement shape to be reported;
     *                                 must be at least 2.
     * @param excludedSqlFragments
     *                                 The SQL fragments to skip, matched
     *                                 case-insensitively against the
     *                                 normalized statement text.
     * @return One {@link Finding} per distinct {@code SELECT}/{@code WITH}
     *         shape at or above the threshold, sorted by count descending
     *         then statement — its {@link Finding#description() description}
     *         names the count and the statement; an empty list when none
     *         reaches the threshold.
     * @throws IllegalArgumentException
     *                                      If {@code threshold} is less than
     *                                      2.
     * @throws IllegalStateException
     *                                      If nothing was captured, so the
     *                                      audit would otherwise report
     *                                      nothing vacuously.
     */
    public List<Finding> audit(final int threshold,
            final Collection<String> excludedSqlFragments) {
        if (threshold < 2) {
            throw new IllegalArgumentException(
                    "threshold must be at least 2, was " + threshold);
        }
        if (sqlCapturer.capturedSql().isEmpty()) {
            throw new IllegalStateException(
                    SqlCapturingStatementInspector.EMPTY_CAPTURE_MESSAGE);
        }

        final Map<String, Long> countsByNormalizedStatement =
                aggregateByNormalizedStatement(sqlCapturer.executionCounts());

        return countsByNormalizedStatement.entrySet().stream()
                .filter(entry -> isSelect(entry.getKey()))
                .filter(entry -> !isExcluded(entry.getKey(),
                        excludedSqlFragments))
                .filter(entry -> entry.getValue() >= threshold)
                .sorted(Comparator
                        .<Map.Entry<String, Long>>comparingLong(
                                Map.Entry::getValue)
                        .reversed().thenComparing(Map.Entry::getKey))
                .<Finding>map(entry -> new RepeatedStatementFinding(
                        entry.getKey(), entry.getValue()))
                .toList();
    }

    private Map<String, Long> aggregateByNormalizedStatement(
            final Map<String, Long> executionCounts) {
        return executionCounts.entrySet().stream()
                .collect(Collectors.groupingBy(
                        entry -> sqlCapturer.normalize(entry.getKey()),
                        Collectors.summingLong(Map.Entry::getValue)));
    }

    private boolean isSelect(final String normalizedSql) {
        final String upperCasedSql = normalizedSql.toUpperCase(Locale.ROOT);
        return upperCasedSql.startsWith("SELECT")
                || upperCasedSql.startsWith("WITH");
    }

    private boolean isExcluded(final String normalizedSql,
            final Collection<String> excludedSqlFragments) {
        final String lower = normalizedSql.toLowerCase(Locale.ROOT);
        return excludedSqlFragments.stream()
                .anyMatch(f -> lower.contains(f.toLowerCase(Locale.ROOT)));
    }
}
