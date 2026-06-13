package io.github.databaseaudits.catalog;

import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * One index and its key columns in index order — the value record
 * {@link IndexCatalog} reads from the platform's catalog.
 *
 * <p>
 * A {@code columns} entry is {@code null} where the index part is not a plain
 * column (a PostgreSQL/MySQL expression part, or a MySQL prefix part like
 * {@code col(10)}); a {@code null} never matches a column by name, which is
 * exactly the conservative behavior the audits want. {@code partial} marks a
 * PostgreSQL index with a {@code WHERE} predicate, which does not reliably
 * serve other lookups.
 */
public record IndexDefinition(String tableName, String indexName,
        boolean unique, boolean primary, boolean partial,
        List<@Nullable String> columns) {
    /**
     * Whether any key part is not a plain column (expression or prefix part —
     * represented as NULL).
     */
    public boolean hasExpressionColumn() {
        return columns.stream().anyMatch(Objects::isNull);
    }
}
