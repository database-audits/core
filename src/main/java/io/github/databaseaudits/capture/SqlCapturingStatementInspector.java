package io.github.databaseaudits.capture;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
 * placeholders (no bind values), which is what the EXPLAIN audits need. It
 * also tallies how many times each distinct statement is captured
 * ({@link #executionCounts()}), which {@code RepeatedStatementAudit} reads to
 * detect N+1 statement bursts.
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

    /** How many times each distinct statement has been captured so far. */
    private final Map<String, LongAdder> executionCounts =
            new ConcurrentHashMap<>();

    @Override
    public String inspect(final String sql) {
        if (sql != null && !sql.isBlank()) {
            captured.add(sql);
            executionCounts.computeIfAbsent(sql, key -> new LongAdder())
                    .increment();
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
     * Returns a snapshot of how many times each distinct statement has been
     * captured so far — one increment per {@link #inspect(String)} call, i.e.
     * per statement Hibernate prepares. Counts accumulate for this capturer's
     * whole lifetime; call {@link #clear()} first for a count scoped to one
     * workload.
     *
     * @return An immutable copy of the raw statement text to its capture
     *         count.
     */
    public Map<String, Long> executionCounts() {
        return executionCounts.entrySet().stream().collect(Collectors
                .toUnmodifiableMap(Map.Entry::getKey, e -> e.getValue().sum()));
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
     * Clears the capture, including the execution counts. Not used by the
     * bundled audits, but handy if a consumer wants per-test isolation (e.g.
     * capture only one method's SQL) — call it from a {@code @BeforeEach}.
     */
    public void clear() {
        captured.clear();
        executionCounts.clear();
    }
}
