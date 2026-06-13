package io.github.databaseaudits.audit.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.databaseaudits.audit.catalog.RedundantIndexAudit.Redundancy;
import io.github.databaseaudits.catalog.IndexCatalog;
import io.github.databaseaudits.catalog.IndexDefinition;

/**
 * Pure unit tests of the audit's redundancy logic with hand-built
 * {@link IndexDefinition} values; the injected catalog is an inert mock. The
 * end-to-end path is covered by {@link CatalogAuditsIT}.
 */
class RedundantIndexAuditTest {

    private final RedundantIndexAudit audit =
            new RedundantIndexAudit(mock(IndexCatalog.class));

    private static IndexDefinition plain(final String table, final String name,
            final String... columns) {
        return new IndexDefinition(table, name, false, false, false,
                Arrays.asList(columns));
    }

    private static IndexDefinition unique(final String table, final String name,
            final String... columns) {
        return new IndexDefinition(table, name, true, false, false,
                Arrays.asList(columns));
    }

    private static IndexDefinition primaryKey(final String table,
            final String name, final String... columns) {
        return new IndexDefinition(table, name, true, true, false,
                Arrays.asList(columns));
    }

    @Test
    void testFindRedundancies_LeadingPrefixOfWiderIndex_IsRedundant() {
        final List<Redundancy> redundancies = audit.findRedundancies(List.of(
                plain("orders", "idx_customer", "customer_id"), plain("orders",
                        "idx_customer_created", "customer_id", "created_at")));

        assertThat(redundancies)
                .as("Leading-prefix index should be reported as redundant.")
                .containsExactly(new Redundancy("orders",
                        "idx_customer", "idx_customer_created"));
    }

    @Test
    void testFindRedundancies_ColumnPermutation_IsNotPrefix() {
        assertThat(
                audit.findRedundancies(List.of(plain("t", "idx_ab", "a", "b"),
                        plain("t", "idx_ba_c", "b", "a", "c"))))
                .as("Column permutation should not be considered a leading prefix.")
                .isEmpty();
    }

    @Test
    void testFindRedundancies_UniqueAndPrimaryKeyIndexes_NeverReportedButCanCover() {
        final List<Redundancy> redundancies = audit.findRedundancies(List.of(
                primaryKey("t", "t_pkey", "id", "version"),
                plain("t", "idx_id", "id"), unique("t", "uq_code", "code")));

        assertThat(redundancies)
                .as("Plain index covered by primary key should be reported, but not the PK or unique index.")
                .containsExactly(new Redundancy("t", "idx_id", "t_pkey"));
    }

    @Test
    void testFindRedundancies_IdenticalKeyColumns_ReportsLexicographicallyFirstAsRedundant() {
        assertThat(audit.findRedundancies(
                List.of(plain("t", "idx_b", "a"), plain("t", "idx_a", "a"))))
                .as("With identical key columns, the lexicographically first index should be reported.")
                .containsExactly(new Redundancy("t", "idx_a", "idx_b"));
    }

    @Test
    void testFindRedundancies_PlainDuplicateOfUnique_ReportedRegardlessOfNameOrder() {
        assertThat(audit.findRedundancies(List.of(unique("t", "a_unique", "a"),
                plain("t", "z_duplicate", "a"))))
                .as("Plain duplicate of a unique index should be reported.")
                .containsExactly(
                        new Redundancy("t", "z_duplicate", "a_unique"));
    }

    @Test
    void testFindRedundancies_PartialAndExpressionIndexes_SkippedOnBothSides() {
        final IndexDefinition partial = new IndexDefinition("t", "idx_partial",
                false, false, true, List.of("a"));
        final IndexDefinition expression = new IndexDefinition("t", "idx_expr",
                false, false, false, Arrays.asList((String) null));

        assertThat(audit.findRedundancies(
                List.of(partial, expression, plain("t", "idx_ab", "a", "b"))))
                .as("Partial and expression indexes should be skipped on both candidate and covering sides.")
                .isEmpty();
    }

    @Test
    void testFindRedundancies_IndexesOnDifferentTables_NotCompared() {
        assertThat(audit.findRedundancies(List.of(plain("t1", "idx_a", "a"),
                plain("t2", "idx_ab", "a", "b"))))
                .as("Indexes on different tables should not be compared.")
                .isEmpty();
    }

    @Test
    void testAudit_RedundantIndex_ReportedThenEmptyWhenExcluded() {
        final IndexCatalog indexCatalog = mock(IndexCatalog.class);
        final var auditUnderTest = new RedundantIndexAudit(indexCatalog);
        when(indexCatalog.readAll("public")).thenReturn(List.of(
                plain("orders", "idx_customer", "customer_id"), plain("orders",
                        "idx_customer_created", "customer_id", "created_at")));

        assertThat(auditUnderTest.audit("public", Set.of()))
                .as("Redundant index should be reported.")
                .anySatisfy(violation -> assertThat(violation).contains(
                        "orders.idx_customer is covered by idx_customer_created"));
        assertThat(auditUnderTest.audit("public", Set.of("idx_customer")))
                .as("Excluding the redundant index should produce no violations.")
                .isEmpty();
    }
}
