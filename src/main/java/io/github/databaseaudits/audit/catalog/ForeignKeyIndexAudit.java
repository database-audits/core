package io.github.databaseaudits.audit.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

import io.github.databaseaudits.audit.finding.Finding;
import io.github.databaseaudits.audit.finding.ForeignKeyIndexFinding;
import io.github.databaseaudits.catalog.IndexCatalog;
import io.github.databaseaudits.catalog.IndexDefinition;
import io.github.databaseaudits.jdbc.CatalogQueries;
import io.github.databaseaudits.platform.DatabasePlatform;
import lombok.AllArgsConstructor;

/**
 * Every foreign key must be backed by an index whose <em>leading</em> columns
 * are the FK columns.
 *
 * <p>
 * PostgreSQL and H2-without-referential-integrity do not auto-create an index
 * for a foreign key. A missing FK index means slow child→parent lookups and
 * lock-heavy parent {@code DELETE}/{@code UPDATE} (a sequential scan of the
 * child under a strong lock). On MySQL/MariaDB InnoDB auto-creates a supporting
 * index, so this audit normally passes there — on MariaDB it still catches an
 * index dropped after the fact (permitted while {@code foreign_key_checks} is
 * suspended; MySQL refuses such drops outright). Purely catalog-driven, so
 * deterministic regardless of test data; supports every
 * {@link DatabasePlatform}.
 *
 * <p>
 * Fix: add an index whose leading columns are the FK columns.
 */
@AllArgsConstructor
public class ForeignKeyIndexAudit {
    private final CatalogQueries catalogQueries;
    private final IndexCatalog indexCatalog;
    private final DatabasePlatform platform;

    /** One foreign key with its columns in constraint order. */
    record ForeignKey(String tableName, String constraintName,
            String referencedTable, List<String> columns) {
    }

    String sql() {
        return platform.catalogDialect().foreignKeysSql();
    }

    /**
     * Returns one {@link Finding} for every foreign key with no supporting index
     * whose leading columns are the FK columns, except excluded constraints; an
     * empty list when every FK is backed by one.
     *
     * @param schema
     *                                The schema to scan.
     * @param excludedConstraints
     *                                The constraint names to skip, e.g. a
     *                                join-table FK that is intentionally
     *                                unindexed.
     * @return One {@link Finding} per foreign key with no covering index — its
     *         {@link Finding#description() description} is the reported line; an
     *         empty list when every foreign key is backed by one.
     */
    public List<Finding> audit(final String schema,
            final Set<String> excludedConstraints) {
        final List<ForeignKey> foreignKeys = readForeignKeys(schema);
        final Map<String, List<IndexDefinition>> indexesByTable =
                indexCatalog.readAll(schema).stream().collect(
                        Collectors.groupingBy(IndexDefinition::tableName));
        return foreignKeys.stream().filter(
                fk -> !excludedConstraints.contains(fk.constraintName()))
                .filter(fk -> indexesByTable
                        .getOrDefault(fk.tableName(), List.of()).stream()
                        .noneMatch(index -> covers(index, fk.columns())))
                .<Finding>map(fk -> new ForeignKeyIndexFinding(fk.tableName(),
                        fk.constraintName(), List.copyOf(fk.columns()),
                        fk.referencedTable()))
                .toList();
    }

    /**
     * Whether the index's leading columns cover the FK columns in any order. A
     * partial index does not reliably support the FK, and an expression part
     * (null column) never matches.
     */
    boolean covers(final IndexDefinition index, final List<String> fkColumns) {
        return index.leadingColumnsCover(fkColumns);
    }

    private List<ForeignKey> readForeignKeys(final String schema) {
        final List<Map<String, @Nullable Object>> rows =
                catalogQueries.queryForList(sql(), schema);
        final var byConstraint = new LinkedHashMap<String, ForeignKey>();
        for (final Map<String, @Nullable Object> row : rows) {
            final String table = String.valueOf(row.get("table_name"));
            final String constraint =
                    String.valueOf(row.get("constraint_name"));
            byConstraint
                    .computeIfAbsent(table + ' ' + constraint,
                            key -> new ForeignKey(table, constraint,
                                    String.valueOf(row.get("referenced_table")),
                                    new ArrayList<>()))
                    .columns().add(String.valueOf(row.get("column_name")));
        }
        return List.copyOf(byConstraint.values());
    }
}
