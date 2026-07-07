package io.github.databaseaudits.audit.finding;

/**
 * A column that exists on a mapped table's live schema but no JPA entity
 * maps. When it is {@code NOT NULL} with no default, every entity
 * {@code INSERT} against that table fails at runtime.
 *
 * @param qualifiedTable The mapped table, schema-qualified when the schema is
 *            known.
 * @param column The unmapped column.
 * @param notNullWithoutDefault Whether the column is {@code NOT NULL} with no
 *            default, so it breaks entity inserts.
 */
public record UnmappedColumnFinding(String qualifiedTable, String column,
        boolean notNullWithoutDefault) implements Finding {
    @Override
    public String description() {
        final String base =
                "unmapped column [" + column + "] in table [" + qualifiedTable
                        + "]";
        return notNullWithoutDefault
                ? base + " — NOT NULL with no default breaks entity inserts"
                : base;
    }
}
