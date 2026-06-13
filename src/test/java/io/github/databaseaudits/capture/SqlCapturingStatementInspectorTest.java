package io.github.databaseaudits.capture;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SqlCapturingStatementInspectorTest {
    private final SqlCapturingStatementInspector inspector =
            new SqlCapturingStatementInspector();

    @Test
    void testNormalize_WhitespaceRuns_CollapsesAndTrims() {
        assertThat(inspector.normalize("  select   *\n from\tt  "))
                .isEqualTo("select * from t");
    }

    @Test
    void testNormalize_NullInput_ReturnsEmpty() {
        assertThat(inspector.normalize(null)).isEmpty();
    }

    @Test
    void testInspect_DistinctNonBlankSql_CapturesAndReturnsInputUnchanged() {
        assertThat(inspector.inspect("select 1")).isEqualTo("select 1");
        inspector.inspect("select 1");
        inspector.inspect("select 2");
        inspector.inspect("   ");
        inspector.inspect(null);
        assertThat(inspector.capturedSql())
                .containsExactlyInAnyOrder("select 1", "select 2");
    }

    @Test
    void testClear_AfterCapture_EmptiesTheCapture() {
        inspector.inspect("select 1");
        inspector.clear();
        assertThat(inspector.capturedSql()).isEmpty();
    }
}
