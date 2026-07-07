package io.github.databaseaudits.catalog;

import java.util.List;

/**
 * One foreign key constraint and its columns in constraint order — the value
 * record {@link ForeignKeyCatalog} reads from the platform's catalog.
 *
 * @param tableName The table declaring the foreign key.
 * @param constraintName The foreign-key constraint name.
 * @param referencedTable The table the foreign key references.
 * @param columns The foreign-key columns, in constraint order.
 * @param referencedColumns The referenced columns, in constraint order and
 *            positionally paired with {@code columns}.
 */
public record ForeignKeyDefinition(String tableName, String constraintName,
        String referencedTable, List<String> columns,
        List<String> referencedColumns) {
}
