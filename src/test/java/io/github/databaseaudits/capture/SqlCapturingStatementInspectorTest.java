package io.github.databaseaudits.capture;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class SqlCapturingStatementInspectorTest {
    private final SqlCapturingStatementInspector inspector =
            new SqlCapturingStatementInspector();

    @Test
    void testNormalize_WhitespaceRuns_CollapsesAndTrims() {
        assertThat(inspector.normalize("  select   *\n from\tt  "))
                .as("Whitespace runs (spaces, tabs, newlines) should collapse to single spaces and trim.")
                .isEqualTo("select * from t");
    }

    @Test
    void testNormalize_NullInput_ReturnsEmpty() {
        assertThat(inspector.normalize(null))
                .as("A null input should normalize to an empty string, not throw.")
                .isEmpty();
    }

    @Test
    void testInspect_DistinctNonBlankSql_CapturesAndReturnsInputUnchanged() {
        assertThat(inspector.inspect("select 1"))
                .as("inspect() must return its input unchanged so Hibernate executes the same SQL.")
                .isEqualTo("select 1");
        inspector.inspect("select 1");
        inspector.inspect("select 2");
        inspector.inspect("   ");
        inspector.inspect(null);
        assertThat(inspector.capturedSql())
                .as("Only distinct, non-blank, non-null statements should be captured.")
                .containsExactlyInAnyOrder("select 1", "select 2");
    }

    @Test
    void testExecutionCounts_RepeatedStatement_AccumulatesPerDistinctStatement() {
        inspector.inspect("select 1");
        inspector.inspect("select 1");
        inspector.inspect("select 1");
        inspector.inspect("select 2");

        assertThat(inspector.executionCounts())
                .as("Each distinct statement should be counted separately.")
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("select 1", 3L, "select 2", 1L));
    }

    @Test
    void testExecutionCounts_BlankOrNullSql_NotCounted() {
        inspector.inspect("select 1");
        inspector.inspect("   ");
        inspector.inspect(null);

        assertThat(inspector.executionCounts())
                .as("Blank and null statements should not be counted.")
                .containsExactlyEntriesOf(Map.of("select 1", 1L));
    }

    @Test
    void testExecutionCounts_Snapshot_DoesNotReflectLaterCaptures() {
        inspector.inspect("select 1");
        final Map<String, Long> snapshot = inspector.executionCounts();
        inspector.inspect("select 1");

        assertThat(snapshot)
                .as("A snapshot taken before a later capture should not change.")
                .containsExactlyEntriesOf(Map.of("select 1", 1L));
    }

    @Test
    void testClear_AfterCapture_EmptiesTheCaptureAndCounts() {
        inspector.inspect("select 1");
        inspector.clear();
        assertThat(inspector.capturedSql())
                .as("Clearing should empty the captured SQL.")
                .isEmpty();
        assertThat(inspector.executionCounts())
                .as("Clearing the capture should also reset the execution counts.")
                .isEmpty();
    }
}
