package io.github.databaseaudits.audit.catalog;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.databaseaudits.audit.finding.Finding;
import io.github.databaseaudits.audit.finding.UniqueIndexNullableColumnFinding;
import io.github.databaseaudits.catalog.IndexCatalog;
import io.github.databaseaudits.catalog.IndexDefinition;
import io.github.databaseaudits.jdbc.CatalogQueries;
import io.github.databaseaudits.platform.DatabasePlatform;
import lombok.AllArgsConstructor;

/**
 * No UNIQUE index should include a nullable column.
 *
 * <p>
 * On every supported platform a UNIQUE index or constraint permits any number
 * of rows whose key contains a NULL — so a "unique business key" declared on a
 * nullable column silently admits duplicate rows for whichever rows are
 * missing the key. Usually an oversight; occasionally deliberate (for example
 * a partial-uniqueness design), so partial and expression indexes are skipped
 * rather than flagged. Pass index names to skip as {@code excludedIndexes}.
 * Catalog-driven, deterministic; supports every {@link DatabasePlatform} (the
 * platform's catalog SQL lives in the injected {@link CatalogQueries} and
 * {@link IndexCatalog}).
 *
 * <p>
 * Fix: make the nullable column {@code NOT NULL}, use PostgreSQL 15+
 * {@code NULLS NOT DISTINCT}, or exclude the index if the partial uniqueness is
 * deliberate.
 */
@AllArgsConstructor
public class UniqueIndexNotNullAudit {
    private final CatalogQueries catalogQueries;
    private final IndexCatalog indexCatalog;
    private final DatabasePlatform platform;

    /** A candidate unique index paired with its nullable key columns, if any. */
    private record IndexNullableColumns(String table, String index,
            List<String> nullableKeyColumns) {
    }

    String sql() {
        return platform.catalogDialect().nullableColumnsSql();
    }

    /**
     * Returns one {@link Finding} for every UNIQUE index whose key includes a
     * nullable column, except the excluded ones; an empty list when no UNIQUE
     * index has a nullable key column.
     *
     * @param schema
     *                            The schema to scan.
     * @param excludedIndexes
     *                            The index names to skip.
     * @return One {@link Finding} per UNIQUE index with a nullable key column
     *         — its {@link Finding#description() description} is the reported
     *         line; an empty list when none has one.
     */
    public List<Finding> audit(final String schema,
            final Set<String> excludedIndexes) {
        final Set<String> nullableColumns = readNullableColumns(schema);
        return indexCatalog.readAll(schema).stream()
                .filter(this::isCandidate)
                .filter(index -> !excludedIndexes.contains(index.indexName()))
                .map(index -> nullableKeyColumnsOf(index, nullableColumns))
                .filter(candidate -> !candidate.nullableKeyColumns().isEmpty())
                .sorted(Comparator.comparing(IndexNullableColumns::table)
                        .thenComparing(IndexNullableColumns::index))
                .<Finding>map(
                        candidate -> new UniqueIndexNullableColumnFinding(
                                candidate.table(), candidate.index(),
                                candidate.nullableKeyColumns()))
                .toList();
    }

    private Set<String> readNullableColumns(final String schema) {
        return catalogQueries.queryForList(sql(), schema).stream()
                .map(r -> canonical(String.valueOf(r.get("table_name")),
                        String.valueOf(r.get("column_name"))))
                .collect(Collectors.toSet());
    }

    private boolean isCandidate(final IndexDefinition index) {
        return index.unique() && !index.primary() && !index.partial()
                && !index.hasExpressionColumn();
    }

    private IndexNullableColumns nullableKeyColumnsOf(
            final IndexDefinition index, final Set<String> nullableColumns) {
        final List<String> nullableKeyColumns = index.columns().stream()
                .filter(column -> nullableColumns
                        .contains(canonical(index.tableName(), column)))
                .toList();
        return new IndexNullableColumns(index.tableName(), index.indexName(),
                nullableKeyColumns);
    }

    private static String canonical(final String table, final String column) {
        return (table + '.' + column).toLowerCase(Locale.ROOT);
    }
}
