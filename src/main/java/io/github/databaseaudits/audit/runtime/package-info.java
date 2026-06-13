/**
 * Runtime SQL-capture audits — inspect SQL captured by
 * {@link io.github.databaseaudits.capture.SqlCapturingStatementInspector}
 * during test execution. Platform-agnostic: these audits work on every
 * supported {@link io.github.databaseaudits.platform.DatabasePlatform}.
 *
 * <p>
 * EXPLAIN-driven plan audits (PostgreSQL 16+ only) live in the
 * {@code audit.runtime.plan} sub-package.
 */
package io.github.databaseaudits.audit.runtime;