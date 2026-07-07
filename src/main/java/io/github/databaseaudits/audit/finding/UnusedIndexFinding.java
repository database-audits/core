package io.github.databaseaudits.audit.finding;

/**
 * An index no captured statement's plan uses — pure write-amplification cost
 * with no observed read benefit.
 *
 * @param table The table the index belongs to.
 * @param index The unused index.
 */
public record UnusedIndexFinding(String table, String index)
        implements Finding {
    @Override
    public String description() {
        return "%s.%s is used by no captured statement's plan".formatted(table,
                index);
    }
}
