package io.github.databaseaudits.audit.finding;

import java.util.List;

/**
 * A foreign-key relationship enforced by more than one constraint.
 *
 * @param table The table declaring the foreign key.
 * @param columns The foreign-key columns, in constraint order.
 * @param referencedTable The table the foreign key references.
 * @param referencedColumns The referenced columns, in constraint order.
 * @param constraints The duplicate constraint names, sorted.
 */
public record DuplicateForeignKeyFinding(String table, List<String> columns,
        String referencedTable, List<String> referencedColumns,
        List<String> constraints) implements Finding {
    @Override
    public String description() {
        return "%s: FOREIGN KEY (%s) REFERENCES %s (%s) is duplicated by constraints [%s]"
                .formatted(table, String.join(", ", columns), referencedTable,
                        String.join(", ", referencedColumns),
                        String.join(", ", constraints));
    }
}
