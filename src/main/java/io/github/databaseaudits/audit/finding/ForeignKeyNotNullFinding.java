package io.github.databaseaudits.audit.finding;

/**
 * A nullable foreign-key column (a logically mandatory relationship whose column
 * was never made {@code NOT NULL}).
 *
 * @param table The table declaring the foreign key.
 * @param column The nullable foreign-key column.
 * @param constraint The foreign-key constraint name.
 */
public record ForeignKeyNotNullFinding(String table, String column,
        String constraint) implements Finding {
    @Override
    public String description() {
        return "%s.%s (%s) is nullable".formatted(table, column, constraint);
    }
}
