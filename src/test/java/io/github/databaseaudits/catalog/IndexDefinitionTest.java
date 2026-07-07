package io.github.databaseaudits.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class IndexDefinitionTest {
    @Test
    void testHasExpressionColumn_NullKeyPart_ReturnsTrueOtherwiseFalse() {
        assertThat(new IndexDefinition("t", "i", false, false, false,
                Arrays.asList(null, "a")).hasExpressionColumn()).isTrue();
        assertThat(new IndexDefinition("t", "i", false, false, false,
                List.of("a", "b")).hasExpressionColumn()).isFalse();
    }

    @Test
    void testLeadingColumnsCover_ExactLeadingColumns_Covers() {
        final var index = new IndexDefinition("t", "i", false, false, false,
                List.of("a", "b"));

        assertThat(index.leadingColumnsCover(List.of("a", "b"))).isTrue();
        assertThat(index.leadingColumnsCover(List.of("a"))).isTrue();
    }

    @Test
    void testLeadingColumnsCover_PermutedOrder_Covers() {
        final var index = new IndexDefinition("t", "i", false, false, false,
                List.of("b", "a", "c"));

        assertThat(index.leadingColumnsCover(List.of("a", "b"))).isTrue();
    }

    @Test
    void testLeadingColumnsCover_PartialIndex_NeverCovers() {
        final var index = new IndexDefinition("t", "i", false, false, true,
                List.of("a"));

        assertThat(index.leadingColumnsCover(List.of("a"))).isFalse();
    }

    @Test
    void testLeadingColumnsCover_ExpressionPart_NeverMatches() {
        final var index = new IndexDefinition("t", "i", false, false, false,
                Arrays.asList(null, "a"));

        assertThat(index.leadingColumnsCover(List.of("a"))).isFalse();
    }

    @Test
    void testLeadingColumnsCover_IndexNarrowerThanRequested_NeverCovers() {
        final var index = new IndexDefinition("t", "i", false, false, false,
                List.of("a"));

        assertThat(index.leadingColumnsCover(List.of("a", "b"))).isFalse();
    }
}
