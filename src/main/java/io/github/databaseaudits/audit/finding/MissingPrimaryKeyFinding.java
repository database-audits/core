package io.github.databaseaudits.audit.finding;

/**
 * A base table with no {@code PRIMARY KEY}. The column(s) that should form the
 * key cannot be inferred from the catalog, so a generated fix is necessarily a
 * template.
 *
 * @param table The table with no primary key.
 */
public record MissingPrimaryKeyFinding(String table) implements Finding {
    @Override
    public String description() {
        return table;
    }
}
