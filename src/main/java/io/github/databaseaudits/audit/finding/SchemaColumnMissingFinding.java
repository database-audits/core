package io.github.databaseaudits.audit.finding;

/**
 * A column that a JPA entity maps but the live table does not contain. The
 * mapped SQL type is captured (though not shown in {@link #description()}) so a
 * fix can emit a precise {@code ADD COLUMN}.
 *
 * @param qualifiedTable The mapped table, schema-qualified when the mapping
 *            carries a schema.
 * @param column The missing column.
 * @param expectedType The column's mapped SQL type.
 */
public record SchemaColumnMissingFinding(String qualifiedTable, String column,
        String expectedType) implements Finding {
    @Override
    public String description() {
        return "missing column [" + column + "] in table [" + qualifiedTable
                + "]";
    }
}
