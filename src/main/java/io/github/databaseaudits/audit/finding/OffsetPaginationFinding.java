package io.github.databaseaudits.audit.finding;

/**
 * A captured query paginated with {@code OFFSET} (or MySQL/MariaDB's
 * {@code LIMIT <offset>, <count>} form), which scales linearly with page
 * depth. The fix is a code change (keyset pagination), not schema DDL, so any
 * emitted remediation is advisory.
 *
 * @param statement The normalized offending statement.
 */
public record OffsetPaginationFinding(String statement) implements Finding {
    @Override
    public String description() {
        return statement;
    }
}
