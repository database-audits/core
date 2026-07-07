package io.github.databaseaudits.audit.finding;

/**
 * A mutable root entity mapped with no {@code @Version} attribute, so
 * concurrent transactions can silently overwrite each other's changes.
 *
 * @param entityName The mapped entity's name.
 * @param table The entity's physical table name.
 */
public record MissingVersionAttributeFinding(String entityName, String table)
        implements Finding {
    @Override
    public String description() {
        return "%s (table %s) has no @Version attribute — concurrent updates can silently overwrite each other"
                .formatted(entityName, table);
    }
}
