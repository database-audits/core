/**
 * EXPLAIN-driven plan audits — PostgreSQL 16+ only.
 *
 * <p>
 * These audits capture the SQL Hibernate executes at runtime (via
 * {@link io.github.databaseaudits.capture.SqlCapturingStatementInspector}) and
 * then run {@code EXPLAIN (GENERIC_PLAN, FORMAT JSON)} with planner-penalty
 * settings (e.g. {@code SET enable_seqscan = off}) to determine whether an
 * index can serve each access path. A surviving penalized node proves no index
 * exists for that path. The technique is unique to PostgreSQL; every audit in
 * this package throws {@link UnsupportedOperationException} on any other
 * platform via {@link io.github.databaseaudits.plan.QueryPlanExplainer#requirePlanAuditSupport(String)}.
 *
 * <p>
 * Platform-agnostic runtime audits (e.g.
 * {@link io.github.databaseaudits.audit.runtime.UnconditionalMutationAudit})
 * live in the parent {@code audit.runtime} package.
 */
package io.github.databaseaudits.audit.runtime.plan;