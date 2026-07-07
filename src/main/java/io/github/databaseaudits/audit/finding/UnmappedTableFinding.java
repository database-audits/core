package io.github.databaseaudits.audit.finding;

/**
 * A physical base table that exists in the live schema but no JPA entity
 * maps.
 *
 * @param qualifiedTable The unmapped table, schema-qualified when the schema
 *            is known.
 */
public record UnmappedTableFinding(String qualifiedTable) implements Finding {
    @Override
    public String description() {
        return "unmapped table [" + qualifiedTable
                + "] exists in the schema but no entity maps it";
    }
}
