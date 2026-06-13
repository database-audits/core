package io.github.databaseaudits.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.databaseaudits.platform.DatabasePlatform;

class QueryPlanExplainerTest {

    // the pure methods under test never touch the DataSource
    private final QueryPlanExplainer explainer =
            new QueryPlanExplainer(null, DatabasePlatform.POSTGRESQL);

    @Test
    void testToGenericPlanSql_QuestionMarkPlaceholders_RewritesToNumberedDollarParams() {
        assertThat(explainer
                .toGenericPlanSql("select * from t where a = ? and b = ?"))
                .isEqualTo("select * from t where a = $1 and b = $2");
    }

    @Test
    void testToGenericPlanSql_QuestionMarkInStringLiteral_LeavesItUntouched() {
        assertThat(explainer.toGenericPlanSql("where note = 'a?b' and x = ?"))
                .isEqualTo("where note = 'a?b' and x = $1");
    }

    @Test
    void testToGenericPlanSql_QuestionMarkInQuotedIdentifier_LeavesItUntouched() {
        assertThat(explainer
                .toGenericPlanSql("select \"we?rd\" from t where x = ?"))
                .isEqualTo("select \"we?rd\" from t where x = $1");
    }

    @Test
    void testToGenericPlanSql_EscapedSingleQuotes_KeepsQuoteState() {
        assertThat(explainer.toGenericPlanSql("where s = 'it''s' and x = ?"))
                .isEqualTo("where s = 'it''s' and x = $1");
    }

    @Test
    void testToGenericPlanSql_NoPlaceholders_LeavesStatementUnchanged() {
        assertThat(explainer.toGenericPlanSql("select 1"))
                .isEqualTo("select 1");
    }

    @Test
    void testTextOf_FieldPresentOrMissing_ReadsValueOrNull() throws Exception {
        final JsonNode node =
                new ObjectMapper().readTree("{\"Node Type\":\"Seq Scan\"}");
        assertThat(explainer.textOf(node, "Node Type")).isEqualTo("Seq Scan");
        assertThat(explainer.textOf(node, "Missing")).isNull();
    }

    @Test
    void testRequirePlanAuditSupport_PostgresVersusOtherPlatform_PassesElseThrowsNamingAudit() {
        assertThatCode(() -> explainer.requirePlanAuditSupport("SomeAudit"))
                .doesNotThrowAnyException();
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(
                        () -> new QueryPlanExplainer(null, DatabasePlatform.H2)
                                .requirePlanAuditSupport("SomeAudit"))
                .withMessageContaining("SomeAudit").withMessageContaining("H2");
    }

    @Test
    void testPlanWith_PlannerSetting_SetsExplainsResetsAndReturnsPlanNode()
            throws SQLException {
        final Statement statement = explainingStatement(
                "[{\"Plan\":{\"Node Type\":\"Seq Scan\"}}]");

        final JsonNode plan = jdbcExplainer().planWith(
                "select * from t where a = ?", "enable_seqscan = off");

        assertThat(plan.get("Node Type").asText()).isEqualTo("Seq Scan");
        verify(statement).execute("SET enable_seqscan = off");
        verify(statement).executeQuery(
                "EXPLAIN (GENERIC_PLAN, FORMAT JSON) select * from t where a = $1");
        verify(statement).execute("RESET enable_seqscan");
    }

    @Test
    void testPlanWith_SettingWithoutEquals_ResetsBareName()
            throws SQLException {
        final Statement statement = explainingStatement(
                "[{\"Plan\":{\"Node Type\":\"Seq Scan\"}}]");

        jdbcExplainer().planWith("select 1", "geqo");

        verify(statement).execute("SET geqo");
        verify(statement).execute("RESET geqo");
    }

    @Test
    void testPlanWith_SqlException_ThrowsCouldNotExplain() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLException("boom"));

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> jdbcExplainer().planWith("select 1"))
                .withMessageContaining("Could not EXPLAIN");
    }

    @Test
    void testPlanWith_UnparsableExplainOutput_ThrowsCouldNotParse()
            throws SQLException {
        explainingStatement("this is not json");

        assertThatExceptionOfType(RuntimeException.class)
                .isThrownBy(() -> jdbcExplainer().planWith("select 1"))
                .withMessageContaining("Could not parse");
    }

    private final DataSource dataSource = mock(DataSource.class);

    private QueryPlanExplainer jdbcExplainer() {
        return new QueryPlanExplainer(dataSource, DatabasePlatform.POSTGRESQL);
    }

    /**
     * Wires DataSource→Connection→Statement mocks whose EXPLAIN query returns
     * {@code planJson}.
     */
    private Statement explainingStatement(final String planJson)
            throws SQLException {
        final Connection connection = mock(Connection.class);
        final Statement statement = mock(Statement.class);
        final ResultSet resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(
                org.mockito.ArgumentMatchers.startsWith("EXPLAIN")))
                .thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString(1)).thenReturn(planJson);
        return statement;
    }
}
