package io.github.databaseaudits.audit.finding;

/**
 * A plan-based runtime finding: one captured statement whose query plan shows an
 * access path (a {@code WHERE} filter, {@code ORDER BY} sort, or {@code JOIN})
 * that no index serves. The offending plan node(s) are described in
 * {@code planDetail} rather than as discrete columns, so a generated fix is a
 * best-effort index suggestion.
 *
 * @param statement The captured SQL statement whose plan was examined.
 * @param planDetail The human-readable description of the offending plan
 *            node(s), joined as the audit reports them.
 */
public record PlanIndexFinding(String statement, String planDetail)
        implements Finding {
    @Override
    public String description() {
        return planDetail + "\n      " + statement;
    }
}
