package io.github.databaseaudits.audit.runtime.plan;

/**
 * The PostgreSQL {@code EXPLAIN (FORMAT JSON)} field names and {@code Node Type}
 * values the plan-based audits match on, centralized so the production detection
 * logic carries no repeated magic strings. They mirror PostgreSQL's plan-tree
 * output.
 *
 * <p>
 * The audit unit tests build their fixture plans as literal JSON text — a Java
 * constant cannot sit inside a JSON string literal — so these constants are used
 * by the production code only, not the tests.
 */
final class PlanJson {
    // EXPLAIN JSON field names.
    static final String NODE_TYPE = "Node Type";
    static final String RELATION_NAME = "Relation Name";
    static final String PLANS = "Plans";
    static final String PARENT_RELATIONSHIP = "Parent Relationship";
    static final String FILTER = "Filter";
    static final String SORT_KEY = "Sort Key";
    static final String HASH_COND = "Hash Cond";
    static final String MERGE_COND = "Merge Cond";
    static final String JOIN_FILTER = "Join Filter";

    // "Node Type" values the audits detect.
    static final String SEQ_SCAN = "Seq Scan";
    static final String SORT = "Sort";
    static final String INCREMENTAL_SORT = "Incremental Sort";
    static final String HASH_JOIN = "Hash Join";
    static final String MERGE_JOIN = "Merge Join";
    static final String NESTED_LOOP = "Nested Loop";
    static final String HASH = "Hash";
    static final String MATERIALIZE = "Materialize";
    static final String MEMOIZE = "Memoize";

    // "Parent Relationship" value marking a join's inner (rescanned) child.
    static final String INNER = "Inner";

    private PlanJson() {
    }
}
