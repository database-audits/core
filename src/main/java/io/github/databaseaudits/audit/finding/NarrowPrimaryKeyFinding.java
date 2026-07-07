package io.github.databaseaudits.audit.finding;

/**
 * A primary key column declared with an integer type narrower than
 * {@code bigint} — at risk of key exhaustion as the table grows.
 *
 * @param table The table declaring the primary key.
 * @param column The narrow primary-key column.
 * @param dataType The column's declared data type.
 */
public record NarrowPrimaryKeyFinding(String table, String column,
        String dataType) implements Finding {
    @Override
    public String description() {
        return "%s.%s primary key type %s is narrower than bigint — risks key exhaustion"
                .formatted(table, column, dataType);
    }
}
