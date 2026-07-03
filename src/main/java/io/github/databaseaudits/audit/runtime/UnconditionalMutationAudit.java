package io.github.databaseaudits.audit.runtime;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.databaseaudits.capture.SqlCapturingStatementInspector;
import lombok.AllArgsConstructor;

/**
 * No {@code UPDATE} or {@code DELETE} without a {@code WHERE} clause may be
 * executed.
 *
 * <p>
 * An unconditional mutation rewrites or wipes an entire table — a destroyed
 * fixture in a test, a data-loss incident in production, usually an accidental
 * derived-delete or a {@code @Modifying} {@code @Query} missing its predicate.
 * Detection is a literal token scan for a leading {@code UPDATE}/{@code DELETE}
 * with no {@code WHERE}; the word "where" inside a string literal could in
 * theory mask one, but that is negligible with bind parameters. Reads
 * {@link SqlCapturingStatementInspector} (run after the workload); throws on an
 * empty capture rather than reporting nothing vacuously. Pass a deliberate
 * full-table statement (normalized text) as {@code excludedStatements}.
 *
 * <p>
 * Fix: add a {@code WHERE} clause, or exclude a deliberate full-table
 * statement.
 */
@AllArgsConstructor
public class UnconditionalMutationAudit {
    private final SqlCapturingStatementInspector sqlCapturer;

    /**
     * Returns every captured {@code UPDATE}/{@code DELETE} with no
     * {@code WHERE} clause (normalized), except the excluded statements; an
     * empty list when none.
     *
     * @param excludedStatements
     *                               The normalized statement texts to skip,
     *                               matched case-insensitively against the
     *                               normalized statement text.
     * @return One normalized statement per captured unconditional
     *         {@code UPDATE}/{@code DELETE}; an empty list when none.
     * @throws IllegalStateException
     *                                   If nothing was captured, so the audit
     *                                   would otherwise report nothing
     *                                   vacuously.
     */
    public List<String> audit(final Set<String> excludedStatements) {
        final Set<String> capturedSql = sqlCapturer.capturedSql();
        if (capturedSql.isEmpty()) {
            throw new IllegalStateException(
                    SqlCapturingStatementInspector.EMPTY_CAPTURE_MESSAGE);
        }
        final Set<String> excludedLowerCased = excludedStatements.stream()
                .map(statement -> statement.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        return capturedSql.stream().map(sqlCapturer::normalize)
                .filter(this::isUnconditionalMutation)
                .filter(sql -> !excludedLowerCased
                        .contains(sql.toLowerCase(Locale.ROOT)))
                .distinct().sorted().toList();
    }

    private boolean isUnconditionalMutation(final String normalizedSql) {
        final String upper = normalizedSql.toUpperCase(Locale.ROOT);
        return (upper.startsWith("UPDATE ") || upper.startsWith("DELETE "))
                && !upper.contains(" WHERE ");
    }
}
