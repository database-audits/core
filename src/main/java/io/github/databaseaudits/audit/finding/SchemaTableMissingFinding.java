package io.github.databaseaudits.audit.finding;

/**
 * A physical table that a JPA entity maps but the live schema does not contain.
 *
 * @param qualifiedTable The mapped table, schema-qualified when the mapping
 *            carries a schema.
 */
public record SchemaTableMissingFinding(String qualifiedTable)
        implements Finding {
    @Override
    public String description() {
        return "missing table [" + qualifiedTable + "]";
    }
}
