package io.github.databaseaudits.capture;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Records every SQL statement Hibernate executes, so the runtime audits can
 * inspect the real SQL the repositories generate — derived queries,
 * {@code @Query} (JPQL and native), and EntityManager/cascade operations all
 * flow through here.
 *
 * <p>
 * It never mutates the SQL — {@link #inspect(String)} returns its input
 * unchanged. The captured set holds the SQL <em>text</em> with {@code ?}
 * placeholders (no bind values), which is what the EXPLAIN audits need.
 *
 * <h2>Registration — turn capture on across the suite</h2> Capture lives on the
 * instance, so the <em>same instance</em> must be both Hibernate's
 * {@code StatementInspector} and the one the audits use. With plain Hibernate —
 * or any dependency-injection container — put the instance itself into the
 * session-factory settings under
 * {@link org.hibernate.cfg.JdbcSettings#STATEMENT_INSPECTOR} when
 * bootstrapping, and hand the audits that same instance. Spring Boot users get
 * exactly this wiring by importing {@code DatabaseAuditTestConfiguration} from
 * the {@code database-audits-spring-boot} module.
 *
 * <p>
 * (Registering by <em>class name</em> via the
 * {@code hibernate.session_factory.statement_inspector} property is
 * <em>not</em> supported here: Hibernate would instantiate a <em>separate</em>
 * capturer whose capture the audits never see.) Only Hibernate-issued SQL is
 * captured; statements run directly over JDBC are not.
 */
public class SqlCapturingStatementInspector implements StatementInspector {
    private static final long serialVersionUID = 1L;

    /**
     * Shared message for the runtime audits when the capture is empty — they
     * throw {@link IllegalStateException} with this rather than returning no
     * violations (a vacuous pass).
     */
    public static final String EMPTY_CAPTURE_MESSAGE =
            """
                    No SQL was captured, so this audit would pass vacuously.
                      * Register capture across the suite (one inspector instance shared by Hibernate and the audits, \
                    e.g. import DatabaseAuditTestConfiguration from database-audits-spring-boot), and
                      * run this audit AFTER your repository integration tests, in the same JVM.""";

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** The distinct SQL statements captured so far. */
    private final Set<String> captured = ConcurrentHashMap.newKeySet();

    @Override
    public String inspect(final String sql) {
        if (sql != null && !sql.isBlank()) {
            captured.add(sql);
        }
        return sql; // must return the SQL unchanged
    }

    /**
     * Returns a snapshot of every distinct statement captured so far by this
     * capturer.
     *
     * @return An immutable copy of all captured SQL statements.
     */
    public Set<String> capturedSql() {
        return Set.copyOf(captured);
    }

    /**
     * Collapses every run of whitespace to a single space and trims — the
     * canonical form the runtime audits match and de-duplicate on. Note this
     * also rewrites whitespace <em>inside</em> string literals, so use it for
     * detection/reporting, not for re-execution.
     *
     * @param sql
     *                The SQL string to normalize.
     * @return The normalized SQL string, or an empty string if {@code sql} is
     *         {@code null}.
     */
    public String normalize(final String sql) {
        return sql == null ? ""
                : WHITESPACE.matcher(sql).replaceAll(" ").strip();
    }

    /**
     * Clears the capture. Not used by the bundled audits, but handy if a
     * consumer wants per-test isolation (e.g. capture only one method's SQL) —
     * call it from a {@code @BeforeEach}.
     */
    public void clear() {
        captured.clear();
    }
}
