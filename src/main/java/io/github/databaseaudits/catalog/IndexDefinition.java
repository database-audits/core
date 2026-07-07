package io.github.databaseaudits.catalog;

import java.util.HashSet;
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
 *
 * @param tableName The table the index belongs to.
 * @param indexName The index name.
 * @param unique Whether the index enforces uniqueness.
 * @param primary Whether the index backs the primary key.
 * @param partial Whether the index has a {@code WHERE} predicate (a PostgreSQL
 *            partial index).
 * @param columns The key columns in index order; an entry is {@code null} for a
 *            part that is not a plain column (expression or prefix part).
 */
public record IndexDefinition(String tableName, String indexName,
        boolean unique, boolean primary, boolean partial,
        List<@Nullable String> columns) {
    /**
     * Whether any key part is not a plain column (expression or prefix part —
     * represented as NULL).
     *
     * @return {@code true} if any key part is an expression or prefix part.
     */
    public boolean hasExpressionColumn() {
        return columns.stream().anyMatch(Objects::isNull);
    }

    /**
     * Whether this index's leading key columns cover the given columns in any
     * order — the rule for an index supporting a foreign key. A partial index
     * never covers; an expression part (null column) never matches.
     *
     * @param columns
     *                    The columns to cover.
     * @return {@code true} if the leading key columns cover {@code columns}.
     */
    public boolean leadingColumnsCover(final List<String> columns) {
        return !partial && this.columns.size() >= columns.size()
                && new HashSet<>(this.columns.subList(0, columns.size()))
                        .containsAll(columns);
    }
}
