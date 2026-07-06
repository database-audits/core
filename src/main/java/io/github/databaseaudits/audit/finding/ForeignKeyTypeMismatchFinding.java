package io.github.databaseaudits.audit.finding;

/**
 * A foreign-key column whose declared type differs from the type of the column
 * it references.
 *
 * @param table The table declaring the foreign key.
 * @param column The foreign-key column.
 * @param columnType The foreign-key column's declared type.
 * @param referencedTable The referenced table.
 * @param referencedColumn The referenced column.
 * @param referencedType The referenced column's declared type.
 * @param constraint The foreign-key constraint name.
 */
public record ForeignKeyTypeMismatchFinding(String table, String column,
        String columnType, String referencedTable, String referencedColumn,
        String referencedType, String constraint) implements Finding {
    @Override
    public String description() {
        return "%s.%s is %s but references %s.%s which is %s (%s)".formatted(
                table, column, columnType, referencedTable, referencedColumn,
                referencedType, constraint);
    }
}
