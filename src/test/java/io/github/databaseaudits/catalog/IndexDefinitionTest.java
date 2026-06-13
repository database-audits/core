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
}
