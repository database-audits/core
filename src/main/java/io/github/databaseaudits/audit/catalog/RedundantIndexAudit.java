package io.github.databaseaudits.audit.catalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.databaseaudits.catalog.IndexCatalog;
import io.github.databaseaudits.catalog.IndexDefinition;
import lombok.AllArgsConstructor;

/**
 * No index should be made redundant by another (advisory hygiene check).
 *
 * <p>
 * An index whose key columns are a leading prefix of another index's key
 * columns is redundant — the wider index already serves the same lookups, while
 * the narrow one still costs write throughput and disk. Ignores UNIQUE/PRIMARY
 * KEY indexes and partial/expression indexes. Most likely to need intentional
 * exceptions (collation/opclass-specific indexes, ASC/DESC variants); pass them
 * as {@code excludedIndexes}. Catalog-driven, deterministic; supports every
 * {@link io.github.databaseaudits.platform.DatabasePlatform DatabasePlatform}
 * (the platform's catalog SQL lives in the injected {@link IndexCatalog}).
 *
 * <p>
 * Fix: drop the narrower index (the wider one serves its lookups), or exclude
 * it.
 */
@AllArgsConstructor
public class RedundantIndexAudit {
    private final IndexCatalog indexCatalog;

    /** One redundant index and the index that covers it. */
    record Redundancy(String tableName, String redundantIndex,
            String coveredBy) {
    }

    /**
     * Returns a description of every index made redundant by a wider one,
     * except the excluded ones; an empty list when no index is redundant.
     *
     * @param schema
     *                            The schema to scan.
     * @param excludedIndexes
     *                            The index names to skip.
     * @return One description per index made redundant by a wider one; an empty
     *         list when no index is redundant.
     */
    public List<String> audit(final String schema,
            final Set<String> excludedIndexes) {
        return findRedundancies(indexCatalog.readAll(schema)).stream()
                .filter(r -> !excludedIndexes.contains(r.redundantIndex()))
                .map(r -> "%s.%s is covered by %s".formatted(r.tableName(),
                        r.redundantIndex(), r.coveredBy()))
                .toList();
    }

    /**
     * A non-unique, non-primary, non-partial, expression-free index is
     * redundant when its key columns are a leading prefix (order-sensitive) of
     * another non-partial, expression-free index on the same table. When two
     * indexes have identical key columns and both are reportable, only the
     * lexicographically first is reported, so the pair appears once.
     */
    List<Redundancy> findRedundancies(final List<IndexDefinition> indexes) {
        final Map<String, List<IndexDefinition>> byTable = indexes.stream()
                .collect(Collectors.groupingBy(IndexDefinition::tableName));
        final List<Redundancy> result = new ArrayList<>();
        for (final List<IndexDefinition> tableIndexes : byTable.values()) {
            final List<IndexDefinition> sorted = tableIndexes.stream()
                    .sorted(Comparator.comparing(IndexDefinition::indexName))
                    .toList();
            for (final IndexDefinition candidate : sorted) {
                if (candidate.unique() || candidate.primary()
                        || candidate.partial()
                        || candidate.hasExpressionColumn()) {
                    continue;
                }
                sorted.stream()
                        .filter(covering -> isCoveredBy(candidate, covering))
                        .findFirst()
                        .ifPresent(covering -> result.add(new Redundancy(
                                candidate.tableName(), candidate.indexName(),
                                covering.indexName())));
            }
        }
        result.sort(Comparator.comparing(Redundancy::tableName)
                .thenComparing(Redundancy::redundantIndex));
        return result;
    }

    private boolean isCoveredBy(final IndexDefinition candidate,
            final IndexDefinition covering) {
        if (covering == candidate || covering.partial()
                || covering.hasExpressionColumn()
                || covering.columns().size() < candidate.columns().size()
                || !covering.columns().subList(0, candidate.columns().size())
                        .equals(candidate.columns())) {
            return false;
        }
        // identical key columns and the covering side equally reportable:
        // report only one direction
        final boolean identicalKeys =
                covering.columns().size() == candidate.columns().size();
        final boolean coveringAlsoReportable =
                !covering.unique() && !covering.primary();
        return !identicalKeys || !coveringAlsoReportable
                || candidate.indexName().compareTo(covering.indexName()) < 0;
    }
}
