package io.github.databaseaudits.plan;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.databaseaudits.platform.DatabasePlatform;
import lombok.AllArgsConstructor;

/**
 * Obtains a PostgreSQL query plan for a captured statement, for the
 * EXPLAIN-based audits.
 *
 * <p>
 * It rewrites JDBC {@code ?} placeholders to {@code $n} and runs
 * {@code EXPLAIN (GENERIC_PLAN, FORMAT JSON)} — the parameter-free "generic
 * plan", so no bind values or test data are needed — with chosen access
 * strategies <em>penalised</em> (e.g. {@code SET enable_seqscan = off}), so a
 * surviving node of the penalised kind (a {@code Seq Scan}, a {@code Sort}, …)
 * proves no index can serve that access path. An injected collaborator, not a
 * static utility, so the audits depend on it explicitly.
 *
 * <p>
 * This technique only exists on PostgreSQL — no other {@link DatabasePlatform}
 * offers a parameter-free generic-plan EXPLAIN with planner-penalty settings —
 * so the plan-based audits call {@link #requirePlanAuditSupport(String)} and
 * fail fast on any other platform.
 *
 * <p>
 * Over the PostgreSQL JDBC driver, the {@link DataSource} must connect with
 * {@code preferQueryMode=simple}: a statement containing {@code $n} can only be
 * EXPLAINed through the simple query protocol (as psql uses) — under the
 * default extended protocol the server counts {@code $n} as a statement
 * parameter and the parameterless Bind fails ({@code bind message supplies 0
 * parameters}). Without it every parameterized statement is skipped, and a
 * wholly parameterized workload then fails the audits' vacuous-run guard.
 */
@AllArgsConstructor
public class QueryPlanExplainer {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final DataSource dataSource;
    private final DatabasePlatform platform;

    /**
     * Fails fast unless the platform supports the generic-plan EXPLAIN
     * technique (PostgreSQL 16+). Called by the plan-based audits before
     * anything else, so a misconfigured platform surfaces as a clear error,
     * never as a vacuous pass or a misleading "nothing could be EXPLAINed".
     *
     * @param auditName
     *                      The name of the calling audit, used in the error
     *                      message.
     * @throws UnsupportedOperationException
     *                                           On any non-PostgreSQL platform.
     */
    public void requirePlanAuditSupport(final String auditName) {
        if (platform != DatabasePlatform.POSTGRESQL) {
            throw new UnsupportedOperationException(
                    ("""
                            %s requires PostgreSQL 16+ — EXPLAIN (GENERIC_PLAN) with enable_* planner settings has no \
                            equivalent on %s. Run the plan-based audits only against PostgreSQL; the catalog audits \
                            support every DatabasePlatform.""")
                            .formatted(auditName, platform));
        }
    }

    /**
     * Plans {@code sql} (with JDBC {@code ?} placeholders rewritten to
     * {@code $n}) via {@code EXPLAIN (GENERIC_PLAN, FORMAT JSON)} after
     * applying each {@code SET <setting>} — e.g. {@code "enable_seqscan = off"}
     * — and returns the root {@code Plan} node. Every setting is {@code RESET}
     * before the connection returns to the pool.
     *
     * @param sql
     *                            The SQL statement with JDBC {@code ?}
     *                            placeholders.
     * @param sessionSettings
     *                            Planner GUC assignments to apply, e.g.
     *                            {@code "enable_seqscan = off"}.
     * @return The root {@code Plan} node from the EXPLAIN JSON output.
     * @throws RuntimeException
     *                              If the statement cannot be planned or its
     *                              plan cannot be parsed; the callers treat
     *                              that as "un-checkable" and skip the
     *                              statement.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public JsonNode planWith(final String sql,
            final String... sessionSettings) {
        final String generic = toGenericPlanSql(sql);
        final String planJson;
        try (Connection connection = dataSource.getConnection();
                Statement st = connection.createStatement()) {
            for (final String setting : sessionSettings) {
                st.execute("SET " + setting);
            }
            try (ResultSet rs = st.executeQuery(
                    "EXPLAIN (GENERIC_PLAN, FORMAT JSON) " + generic)) {
                final var json = new StringBuilder();
                while (rs.next()) {
                    json.append(rs.getString(1));
                }
                planJson = json.toString();
            } finally {
                for (final String setting : sessionSettings) {
                    st.execute("RESET " + settingName(setting));
                }
            }
        } catch (final SQLException e) {
            throw new IllegalStateException("Could not EXPLAIN the statement",
                    e);
        }

        try {
            return OBJECT_MAPPER.readTree(planJson).get(0).get("Plan");
        } catch (final Exception e) {
            throw new RuntimeException("Could not parse EXPLAIN output", e);
        }
    }

    /**
     * {@code "enable_sort = off"} -> {@code "enable_sort"}, so we RESET the
     * right GUC.
     */
    private String settingName(final String setting) {
        final int eq = setting.indexOf('=');
        return (eq < 0 ? setting : setting.substring(0, eq)).strip();
    }

    /**
     * Replaces JDBC {@code ?} placeholders (outside string/identifier literals)
     * with {@code $n}.
     *
     * @param sql
     *                The SQL string with JDBC {@code ?} placeholders.
     * @return The SQL string with {@code $1}, {@code $2}, … positional
     *         parameters.
     */
    String toGenericPlanSql(final String sql) {
        final var sb = new StringBuilder(sql.length() + 8);
        int param = 0;
        char openQuote = 0; // 0 = outside any quote; otherwise the open ' or "
                            // character
        for (int i = 0; i < sql.length(); i++) {
            final char ch = sql.charAt(i);
            if (openQuote == 0) {
                if (ch == '\'' || ch == '"') {
                    openQuote = ch; // opening quote
                    sb.append(ch);
                } else if (ch == '?') {
                    sb.append('$').append(++param);
                } else {
                    sb.append(ch);
                }
            } else {
                if (ch == openQuote) {
                    openQuote = 0; // closing quote
                }
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /**
     * Null-safe read of a string field from a plan node — a pure helper for the
     * audits' plan walking.
     *
     * @param node
     *                  The plan node to read from.
     * @param field
     *                  The JSON field name.
     * @return The field value as text, or {@code null} if the field is absent.
     */
    public String textOf(final JsonNode node, final String field) {
        final JsonNode v = node.get(field);
        return v == null ? null : v.asText();
    }
}
