package io.github.databaseaudits.audit.finding;

/**
 * A single audit violation, carrying the structured facts behind it so callers
 * can render both a human-readable description and a precise remediation.
 *
 * <p>
 * Every core audit returns a {@code List<Finding>}. {@link #description()}
 * reproduces the exact line the audit historically emitted, while each concrete
 * record additionally exposes the identifiers (table, column, index,
 * constraint, referenced type, …) a fix generator needs. The type is
 * {@code sealed} so a consumer can {@code switch} over the whole set
 * exhaustively — adding a finding kind is then a deliberate, compile-checked
 * change everywhere findings are turned into fixes.
 */
public sealed interface Finding
        permits DuplicateForeignKeyFinding, ForeignKeyIndexFinding,
        ForeignKeyNotNullFinding, ForeignKeyTypeMismatchFinding,
        MissingPrimaryKeyFinding, NarrowPrimaryKeyFinding,
        RedundantIndexFinding, SchemaTableMissingFinding,
        SchemaColumnMissingFinding, SchemaColumnTypeMismatchFinding,
        UnconditionalMutationFinding, UniqueIndexNullableColumnFinding,
        PlanIndexFinding {
    /**
     * Returns the human-readable description of this violation — the exact line
     * the audit reports.
     *
     * @return the human-readable description of this violation.
     */
    String description();
}
