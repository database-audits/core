package io.github.databaseaudits.audit.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.databaseaudits.audit.catalog.DuplicateForeignKeyAudit.Duplicate;
import io.github.databaseaudits.audit.finding.Finding;
import io.github.databaseaudits.catalog.ForeignKeyCatalog;
import io.github.databaseaudits.catalog.ForeignKeyDefinition;

/**
 * Pure unit tests of the audit's relationship-grouping logic with hand-built
 * {@link ForeignKeyDefinition} values; the injected {@link ForeignKeyCatalog}
 * is an inert mock. The end-to-end path is covered by {@link CatalogAuditsIT}.
 */
class DuplicateForeignKeyAuditTest {

    private final DuplicateForeignKeyAudit audit =
            new DuplicateForeignKeyAudit(mock(ForeignKeyCatalog.class));

    private static ForeignKeyDefinition fk(final String constraint,
            final List<String> columns, final String referencedTable,
            final List<String> referencedColumns) {
        return new ForeignKeyDefinition("child", constraint, referencedTable,
                columns, referencedColumns);
    }

    @Test
    void testFindDuplicates_TwoConstraintsSameColumns_GroupsIntoOneDuplicate() {
        final List<Duplicate> duplicates = audit.findDuplicates(List.of(
                fk("fk_child_parent", List.of("parent_id"), "parent",
                        List.of("id")),
                fk("fk_child_parent_2", List.of("parent_id"), "parent",
                        List.of("id"))));

        assertThat(duplicates)
                .as("The two constraints enforcing the same relationship should group into one duplicate.")
                .containsExactly(new Duplicate("child", List.of("parent_id"),
                        "parent", List.of("id"),
                        List.of("fk_child_parent", "fk_child_parent_2")));
    }

    @Test
    void testFindDuplicates_PermutedColumnOrder_StillGroupsTogether() {
        final List<Duplicate> duplicates = audit.findDuplicates(List.of(
                fk("fk_ab", List.of("a", "b"), "parent", List.of("x", "y")),
                fk("fk_ba", List.of("b", "a"), "parent", List.of("y", "x"))));

        assertThat(duplicates)
                .as("Permuted-order declarations of the same relationship should group into exactly one duplicate.")
                .hasSize(1);
        assertThat(duplicates.getFirst().constraints())
                .as("Both permuted declarations of the same relationship should be reported together.")
                .containsExactly("fk_ab", "fk_ba");
    }

    @Test
    void testFindDuplicates_SameColumnsDifferentReferencedColumns_NoDuplicate() {
        final List<Duplicate> duplicates = audit.findDuplicates(List.of(
                fk("fk_a", List.of("parent_id"), "parent", List.of("id")),
                fk("fk_b", List.of("parent_id"), "parent", List.of("code"))));

        assertThat(duplicates)
                .as("The same FK column referencing a different column is not a duplicate.")
                .isEmpty();
    }

    @Test
    void testFindDuplicates_DifferentTables_NoDuplicate() {
        final ForeignKeyDefinition onChildA = new ForeignKeyDefinition(
                "child_a", "fk_parent", "parent", List.of("parent_id"),
                List.of("id"));
        final ForeignKeyDefinition onChildB = new ForeignKeyDefinition(
                "child_b", "fk_parent", "parent", List.of("parent_id"),
                List.of("id"));

        assertThat(audit.findDuplicates(List.of(onChildA, onChildB)))
                .as("The same constraint name on two different tables is not a duplicate relationship.")
                .isEmpty();
    }

    @Test
    void testFindDuplicates_NoDuplicates_ReturnsEmpty() {
        assertThat(audit.findDuplicates(List.of(fk("fk_child_parent",
                List.of("parent_id"), "parent", List.of("id")))))
                .as("A single constraint per relationship should produce no duplicate.")
                .isEmpty();
    }

    @Test
    void testAudit_DuplicatePair_ReportsBothConstraints() {
        final ForeignKeyCatalog foreignKeyCatalog =
                mock(ForeignKeyCatalog.class);
        final var auditUnderTest =
                new DuplicateForeignKeyAudit(foreignKeyCatalog);
        when(foreignKeyCatalog.readAll("public")).thenReturn(List.of(
                fk("fk_child_parent", List.of("parent_id"), "parent",
                        List.of("id")),
                fk("fk_child_parent_2", List.of("parent_id"), "parent",
                        List.of("id"))));

        assertThat(auditUnderTest.audit("public", Set.of()))
                .extracting(Finding::description)
                .as("Both duplicate constraints should be named in the finding.")
                .containsExactly(
                        "child: FOREIGN KEY (parent_id) REFERENCES parent (id) is duplicated by constraints [fk_child_parent, fk_child_parent_2]");
    }

    @Test
    void testAudit_ExcludingOneOfThePair_ReturnsNoViolations() {
        final ForeignKeyCatalog foreignKeyCatalog =
                mock(ForeignKeyCatalog.class);
        final var auditUnderTest =
                new DuplicateForeignKeyAudit(foreignKeyCatalog);
        when(foreignKeyCatalog.readAll("public")).thenReturn(List.of(
                fk("fk_child_parent", List.of("parent_id"), "parent",
                        List.of("id")),
                fk("fk_child_parent_2", List.of("parent_id"), "parent",
                        List.of("id"))));

        assertThat(auditUnderTest.audit("public",
                Set.of("fk_child_parent_2")))
                .as("Excluding one of the duplicate pair should leave only one constraint, clearing the finding.")
                .isEmpty();
    }
}
