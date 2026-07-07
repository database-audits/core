package io.github.databaseaudits.audit.finding;

/**
 * A mapped collection fetched eagerly — loaded on every load of its owning
 * entity.
 *
 * @param role The collection's Hibernate role, e.g. {@code com.acme.Order.items}.
 * @param collectionTable The collection's table name.
 */
public record EagerCollectionFetchFinding(String role, String collectionTable)
        implements Finding {
    @Override
    public String description() {
        return "%s (collection table %s) is fetched eagerly — loaded on every owner load"
                .formatted(role, collectionTable);
    }
}
