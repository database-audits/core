package io.github.databaseaudits.audit.finding;

/**
 * An index made redundant by a wider one whose leading key columns it duplicates.
 *
 * @param table The table both indexes belong to.
 * @param redundantIndex The redundant (narrower) index.
 * @param coveredBy The index that already serves its lookups.
 */
public record RedundantIndexFinding(String table, String redundantIndex,
        String coveredBy) implements Finding {
    @Override
    public String description() {
        return "%s.%s is covered by %s".formatted(table, redundantIndex,
                coveredBy);
    }
}
