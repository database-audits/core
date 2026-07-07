package io.github.databaseaudits.audit.runtime;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import io.github.databaseaudits.audit.finding.Finding;
import io.github.databaseaudits.audit.finding.OffsetPaginationFinding;
import io.github.databaseaudits.capture.SqlCapturingStatementInspector;
import lombok.AllArgsConstructor;

/**
 * Advisory: no query should page with {@code OFFSET} (or MySQL/MariaDB's
 * {@code LIMIT <offset>, <count>} comma form).
 *
 * <p>
 * Offset-based pagination — what Spring Data's {@code Pageable} emits by
 * default — makes the database produce and discard every skipped row: page
 * 1000 costs roughly 1000&times; page 1. Detection is a literal token scan of
 * the captured SQL for {@code " OFFSET "} (covering {@code limit ? offset ?}
 * and the standard {@code offset ? rows fetch first ? rows only} Hibernate 6
 * emits on PostgreSQL/H2) or the MySQL/MariaDB comma form
 * {@code limit <offset>, <count>}; a bare {@code limit ?} with neither is not
 * offset pagination and is not flagged. A quoted identifier named
 * {@code "offset"} cannot false-positive the space-delimited check, and bind
 * values are invisible to this scan — the statement <em>shape</em> is the
 * finding, not any particular page depth. Reads
 * {@link SqlCapturingStatementInspector} (run after the workload); throws on
 * an empty capture rather than reporting nothing vacuously. Shallow, bounded
 * pagination is legitimate: pass its SQL fragment (matched case-insensitively)
 * as {@code excludedSqlFragments}.
 *
 * <p>
 * Fix: switch to keyset (seek) pagination
 * ({@code WHERE (sort_key, id) > (?, ?) ORDER BY sort_key, id LIMIT ?}), or
 * exclude the statement if the pagination is deliberately shallow and
 * bounded.
 */
@AllArgsConstructor
public class OffsetPaginationAudit {
    private static final Pattern MYSQL_COMMA_LIMIT_OFFSET =
            Pattern.compile("\\bLIMIT\\s+(?:\\?|\\d+)\\s*,");

    private final SqlCapturingStatementInspector sqlCapturer;

    /**
     * Returns one {@link Finding} for every distinct captured query paginated
     * with {@code OFFSET} (or the MySQL/MariaDB comma form), except statements
     * matching an excluded fragment; an empty list when none paginates that
     * way.
     *
     * @param excludedSqlFragments
     *                                 The SQL fragments to skip, matched
     *                                 case-insensitively against the
     *                                 normalized statement text.
     * @return One {@link Finding} per distinct offset-paginated statement —
     *         its {@link Finding#description() description} is the normalized
     *         statement; an empty list when none paginates that way.
     * @throws IllegalStateException
     *                                   If nothing was captured, so the audit
     *                                   would otherwise report nothing
     *                                   vacuously.
     */
    public List<Finding> audit(final Collection<String> excludedSqlFragments) {
        final Set<String> capturedSql = sqlCapturer.capturedSql();
        if (capturedSql.isEmpty()) {
            throw new IllegalStateException(
                    SqlCapturingStatementInspector.EMPTY_CAPTURE_MESSAGE);
        }
        return capturedSql.stream().map(sqlCapturer::normalize)
                .filter(this::isOffsetPaginated)
                .filter(sql -> !isExcluded(sql, excludedSqlFragments))
                .distinct().sorted()
                .<Finding>map(OffsetPaginationFinding::new).toList();
    }

    private boolean isOffsetPaginated(final String normalizedSql) {
        final String upper = normalizedSql.toUpperCase(Locale.ROOT);
        final boolean isQuery =
                upper.startsWith("SELECT") || upper.startsWith("WITH");
        return isQuery && (upper.contains(" OFFSET ")
                || MYSQL_COMMA_LIMIT_OFFSET.matcher(upper).find());
    }

    private boolean isExcluded(final String normalizedSql,
            final Collection<String> excludedSqlFragments) {
        final String lower = normalizedSql.toLowerCase(Locale.ROOT);
        return excludedSqlFragments.stream()
                .anyMatch(f -> lower.contains(f.toLowerCase(Locale.ROOT)));
    }
}
