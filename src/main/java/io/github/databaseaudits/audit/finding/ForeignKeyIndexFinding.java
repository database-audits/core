package io.github.databaseaudits.audit.finding;

import java.util.List;

/**
 * A foreign key with no supporting index whose leading columns are the foreign
 * key columns.
 *
 * @param table The table declaring the foreign key.
 * @param constraint The foreign-key constraint name.
 * @param columns The foreign-key columns, in constraint order.
 * @param referencedTable The table the foreign key references.
 */
public record ForeignKeyIndexFinding(String table, String constraint,
        List<String> columns, String referencedTable) implements Finding {
    @Override
    public String description() {
        return "%s.%s  ->  FOREIGN KEY (%s) REFERENCES %s".formatted(table,
                constraint, String.join(", ", columns), referencedTable);
    }
}
