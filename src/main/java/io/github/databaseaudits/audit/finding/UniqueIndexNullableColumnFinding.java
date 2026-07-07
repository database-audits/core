package io.github.databaseaudits.audit.finding;

import java.util.List;

/**
 * A UNIQUE index whose key includes one or more nullable columns — rows with a
 * NULL in that column bypass the uniqueness guarantee.
 *
 * @param table The table the index belongs to.
 * @param index The unique index.
 * @param nullableColumns The index's nullable key columns, in index order.
 */
public record UniqueIndexNullableColumnFinding(String table, String index,
        List<String> nullableColumns) implements Finding {
    @Override
    public String description() {
        return "%s.%s is UNIQUE over nullable column(s) [%s] — rows with NULLs bypass uniqueness"
                .formatted(table, index, String.join(", ", nullableColumns));
    }
}
