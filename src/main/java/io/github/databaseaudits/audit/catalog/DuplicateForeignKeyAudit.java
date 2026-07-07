package io.github.databaseaudits.audit.catalog;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import io.github.databaseaudits.audit.finding.DuplicateForeignKeyFinding;
import io.github.databaseaudits.audit.finding.Finding;
import io.github.databaseaudits.catalog.ForeignKeyCatalog;
import io.github.databaseaudits.catalog.ForeignKeyDefinition;
import io.github.databaseaudits.platform.DatabasePlatform;
import lombok.AllArgsConstructor;

/**
 * No relationship should be enforced by more than one foreign key constraint.
 *
 * <p>
 * A duplicate FK constraint — a Liquibase changeset applied twice, a
 * hand-written constraint duplicating a generated one — doubles constraint-check
 * work on every child write and confuses schema tooling. All four platforms
 * permit creating one. Two constraints on the same table are duplicates when
 * they declare the same FK columns to the same referenced table and columns,
 * regardless of column declaration order. Catalog-driven, deterministic;
 * supports every {@link DatabasePlatform} (the platform's catalog SQL lives in
 * the injected {@link ForeignKeyCatalog}).
 *
 * <p>
 * Excluding any one constraint of a duplicate pair drops that relationship's
 * group below two members, which suppresses the finding entirely — the
 * exclusion is per relationship, not per finding.
 *
 * <p>
 * Fix: drop all but one of the duplicate constraints, or exclude one to keep
 * the duplication deliberately.
 */
@AllArgsConstructor
public class DuplicateForeignKeyAudit {
    private final ForeignKeyCatalog foreignKeyCatalog;

    /** One column paired with the column it references. */
    record ColumnPair(String column, String referencedColumn) {
    }

    /**
     * The relationship a foreign key enforces, independent of column
     * declaration order — the identity a duplicate shares with its twin.
     */
    record Relationship(String table, String referencedTable,
            Set<ColumnPair> pairs) {
    }

    /** A relationship enforced by two or more foreign key constraints. */
    record Duplicate(String table, List<String> columns,
            String referencedTable, List<String> referencedColumns,
            List<String> constraints) {
    }

    /**
     * Returns one {@link Finding} for every relationship enforced by more than
     * one foreign key constraint, except where enough duplicates have been
     * excluded to leave at most one; an empty list when no relationship is
     * duplicated.
     *
     * @param schema
     *                                The schema to scan.
     * @param excludedConstraints
     *                                The constraint names to skip.
     * @return One {@link Finding} per duplicated relationship — its
     *         {@link Finding#description() description} is the reported line;
     *         an empty list when no relationship is duplicated.
     */
    public List<Finding> audit(final String schema,
            final Set<String> excludedConstraints) {
        final List<ForeignKeyDefinition> foreignKeys = foreignKeyCatalog
                .readAll(schema).stream()
                .filter(fk -> !excludedConstraints
                        .contains(fk.constraintName()))
                .toList();
        return findDuplicates(foreignKeys).stream()
                .<Finding>map(d -> new DuplicateForeignKeyFinding(d.table(),
                        d.columns(), d.referencedTable(),
                        d.referencedColumns(), d.constraints()))
                .toList();
    }

    /**
     * Groups the foreign keys by relationship — table, referenced table, and
     * the unordered set of column-to-referenced-column pairs — and returns one
     * {@link Duplicate} per relationship enforced by two or more constraints.
     *
     * @param foreignKeys
     *                        The foreign keys to group.
     * @return One {@link Duplicate} per relationship with more than one
     *         constraint, sorted by table then first constraint name.
     */
    List<Duplicate> findDuplicates(
            final List<ForeignKeyDefinition> foreignKeys) {
        final Map<Relationship, List<ForeignKeyDefinition>> byRelationship =
                foreignKeys.stream()
                        .collect(Collectors.groupingBy(
                                DuplicateForeignKeyAudit::relationshipOf,
                                LinkedHashMap::new, Collectors.toList()));

        return byRelationship.values().stream()
                .filter(group -> group.size() >= 2)
                .map(DuplicateForeignKeyAudit::duplicateOf)
                .sorted(Comparator.comparing(Duplicate::table)
                        .thenComparing(d -> d.constraints().getFirst()))
                .toList();
    }

    private static Relationship relationshipOf(final ForeignKeyDefinition fk) {
        final Set<ColumnPair> pairs = IntStream.range(0, fk.columns().size())
                .mapToObj(i -> new ColumnPair(fk.columns().get(i),
                        fk.referencedColumns().get(i)))
                .collect(Collectors.toSet());
        return new Relationship(fk.tableName(), fk.referencedTable(), pairs);
    }

    private static Duplicate duplicateOf(
            final List<ForeignKeyDefinition> group) {
        final ForeignKeyDefinition first = group.getFirst();
        final List<String> constraints = group.stream()
                .map(ForeignKeyDefinition::constraintName).sorted().toList();
        return new Duplicate(first.tableName(), first.columns(),
                first.referencedTable(), first.referencedColumns(),
                constraints);
    }
}
