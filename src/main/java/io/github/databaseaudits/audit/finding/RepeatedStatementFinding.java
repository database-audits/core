package io.github.databaseaudits.audit.finding;

/**
 * A captured {@code SELECT} statement shape executed at least as many times as
 * the audit's threshold — the signature of an N+1 statement burst. The fix is
 * a code change (fetch join, batch size, {@code @EntityGraph}), not schema
 * DDL, so any emitted remediation is advisory.
 *
 * @param statement The normalized statement shape.
 * @param count The number of times the shape was captured.
 */
public record RepeatedStatementFinding(String statement, long count)
        implements Finding {
    @Override
    public String description() {
        return "executed %d times: %s".formatted(count, statement);
    }
}
