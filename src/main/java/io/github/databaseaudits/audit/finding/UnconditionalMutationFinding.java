package io.github.databaseaudits.audit.finding;

/**
 * A captured {@code UPDATE} or {@code DELETE} with no {@code WHERE} clause. The
 * fix is a code change (add a predicate), not schema DDL, so any emitted
 * remediation is advisory.
 *
 * @param statement The normalized offending statement.
 */
public record UnconditionalMutationFinding(String statement)
        implements Finding {
    @Override
    public String description() {
        return statement;
    }
}
