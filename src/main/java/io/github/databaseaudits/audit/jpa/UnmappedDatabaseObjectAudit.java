package io.github.databaseaudits.audit.jpa;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.hibernate.boot.Metadata;
import org.hibernate.mapping.Table;
import org.jspecify.annotations.Nullable;

import io.github.databaseaudits.audit.finding.Finding;
import io.github.databaseaudits.audit.finding.UnmappedColumnFinding;
import io.github.databaseaudits.audit.finding.UnmappedTableFinding;

import jakarta.persistence.EntityManagerFactory;

/**
 * Every physical base table and column in the live schema should be mapped by
 * a JPA entity — the reverse direction of {@link SchemaEntityValidationAudit},
 * which proves every <em>mapping</em> exists in the schema but nothing proves
 * the schema holds only what is mapped.
 *
 * <p>
 * A leftover table keeps accumulating unowned data long after its entity was
 * removed; worse, a live column that is {@code NOT NULL} with no default and
 * no entity maps it makes every entity {@code INSERT} against that table fail
 * at runtime. Only schemas containing at least one mapped table are scanned —
 * never the whole server — and only base tables (a JDBC {@code "TABLE"}), so
 * views are ignored. A Hibernate-generated join/collection table
 * ({@code @JoinTable}, {@code @ElementCollection}'s {@code @CollectionTable})
 * counts as mapped: {@link Metadata#collectTableMappings()} includes it like
 * any other physical table. Reads the boot mapping model from a
 * {@link MappingMetadataIntegrator} captured during bootstrap, exactly like
 * {@link SchemaEntityValidationAudit}, plus a {@link DataSource} to read the
 * live schema.
 *
 * <p>
 * A {@code null} {@link java.sql.Connection#getSchema()} (MySQL/MariaDB
 * commonly report this, since their driver treats the database as the JDBC
 * <em>catalog</em> instead) is never passed on as a JDBC schema pattern: a
 * {@code null} pattern means "every schema" to {@code DatabaseMetaData}, which
 * would silently violate the never-the-whole-server guarantee above. It falls
 * back to {@code ""} — "no schema" — instead, scoping the scan to exactly the
 * catalog's own schema-less tables.
 *
 * <p>
 * Pass a known, acceptable unmapped relation (a {@code table} name or
 * {@code table.column}, optionally schema-qualified as {@code schema.table} /
 * {@code schema.table.column}) as {@code excludedRelations} — for example
 * migration-tool bookkeeping tables (see
 * {@link io.github.databaseaudits.audit.catalog.PrimaryKeyPresenceAudit#LIQUIBASE_BOOKKEEPING_TABLES})
 * or a deliberate denormalization no entity needs to see.
 *
 * <p>
 * Fix: map the object, drop it via a migration, or exclude it.
 */
public class UnmappedDatabaseObjectAudit {
    private final Supplier<@Nullable Metadata> metadataSupplier;
    private final DataSource dataSource;

    /** An unmapped table ({@code column} is {@code null}) or column. */
    private record UnmappedObject(String qualifiedTable,
            @Nullable String column, boolean notNullWithoutDefault) {
    }

    /**
     * Constructs the audit around the boot mapping model and the datasource
     * whose live schema it is checked against.
     *
     * @param metadataSupplier
     *                             supplies the boot {@link Metadata} for the
     *                             persistence unit, resolved when
     *                             {@link #audit(Set)} runs; see
     *                             {@link #forEntityManagerFactory(EntityManagerFactory, DataSource)}.
     * @param dataSource
     *                             the datasource whose live schema is scanned
     *                             for unmapped objects.
     */
    public UnmappedDatabaseObjectAudit(
            final Supplier<@Nullable Metadata> metadataSupplier,
            final DataSource dataSource) {
        this.metadataSupplier = metadataSupplier;
        this.dataSource = dataSource;
    }

    /**
     * Builds an audit for a JPA {@link EntityManagerFactory}, resolving its boot
     * {@link Metadata} from {@link MappingMetadataIntegrator} when the audit
     * runs.
     *
     * @param entityManagerFactory
     *                                 the factory whose mappings to validate
     *                                 against.
     * @param dataSource
     *                                 the datasource whose live schema to scan.
     * @return the audit.
     */
    public static UnmappedDatabaseObjectAudit forEntityManagerFactory(
            final EntityManagerFactory entityManagerFactory,
            final DataSource dataSource) {
        return new UnmappedDatabaseObjectAudit(
                () -> MappingMetadataIntegrator
                        .metadataFor(entityManagerFactory),
                dataSource);
    }

    /**
     * Scans every schema containing at least one mapped table for base tables
     * and columns no entity maps.
     *
     * @param excludedRelations
     *                              relations to skip, each a table name or a
     *                              {@code table.column} pair — optionally
     *                              schema-qualified — matched
     *                              case-insensitively.
     * @return one {@link Finding} per unmapped table or column among the
     *         non-excluded relations; an empty list when the schema holds only
     *         what is mapped.
     * @throws IllegalStateException
     *                                   if the boot mapping model was never
     *                                   captured, or the database metadata
     *                                   cannot be read.
     */
    public List<Finding> audit(final Set<String> excludedRelations) {
        final Metadata metadata = metadataSupplier.get();
        if (metadata == null) {
            throw new IllegalStateException(
                    "JPA mapping metadata was not captured for this EntityManagerFactory; ensure "
                            + "database-audits-core is on the test classpath (it registers a Hibernate "
                            + "Integrator that records the boot metadata) and that the EntityManagerFactory "
                            + "has been built.");
        }
        final Set<String> excludes = excludedRelations.stream()
                .map(UnmappedDatabaseObjectAudit::canonical)
                .collect(Collectors.toSet());

        final List<UnmappedObject> unmapped = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            final DatabaseMetaData databaseMetaData =
                    connection.getMetaData();
            final String catalog = connection.getCatalog();
            // JDBC treats a null schemaPattern as "search every schema" -
            // never scoping the scan, which would break the "never the whole
            // server" guarantee above. MySQL/MariaDB's getSchema() commonly
            // returns null (the driver treats the database as the JDBC
            // catalog instead, see connection.getCatalog() above), so fall
            // back to "" - "no schema" - which JDBC scopes to exactly the
            // catalog's own schema-less tables.
            final String defaultSchema = connection.getSchema() != null
                    ? connection.getSchema()
                    : "";
            final Map<String, Map<String, Set<String>>> mappedColumnsBySchema =
                    collectMappedColumns(metadata, defaultSchema);

            for (final Map.Entry<String, Map<String, Set<String>>> schemaEntry : mappedColumnsBySchema
                    .entrySet()) {
                unmapped.addAll(findUnmappedInSchema(databaseMetaData,
                        catalog, schemaEntry.getKey(), schemaEntry.getValue(),
                        excludes));
            }
        } catch (final SQLException e) {
            throw new IllegalStateException(
                    "Failed to read database metadata for unmapped-object detection.",
                    e);
        }

        return unmapped.stream()
                .sorted(Comparator.comparing(UnmappedObject::qualifiedTable)
                        .thenComparing(o -> o.column() == null ? ""
                                : o.column()))
                .<Finding>map(o -> o.column() == null
                        ? new UnmappedTableFinding(o.qualifiedTable())
                        : new UnmappedColumnFinding(o.qualifiedTable(),
                                o.column(), o.notNullWithoutDefault()))
                .toList();
    }

    /**
     * Groups every mapped physical table's columns by schema (the table's own
     * schema, or {@code defaultSchema} when the mapping carries none) and
     * canonical table name.
     */
    private Map<String, Map<String, Set<String>>> collectMappedColumns(
            final Metadata metadata, final String defaultSchema) {
        final Map<String, Map<String, Set<String>>> bySchema = new HashMap<>();
        for (final Table table : metadata.collectTableMappings()) {
            if (!table.isPhysicalTable()) {
                continue;
            }
            final String schema = table.getSchema() != null
                    ? table.getSchema()
                    : defaultSchema;
            final Set<String> columnNames = table.getColumns().stream()
                    .map(column -> canonical(column.getName()))
                    .collect(Collectors.toSet());
            bySchema.computeIfAbsent(schema, s -> new HashMap<>()).merge(
                    canonical(table.getName()), columnNames, (existing,
                            added) -> {
                        existing.addAll(added);
                        return existing;
                    });
        }
        return bySchema;
    }

    /**
     * Reads the live base tables and columns of one schema and returns every
     * one not mapped and not excluded — a table-level entry for an unmapped
     * table, a column-level entry (only on an already-mapped table) for an
     * unmapped column.
     */
    private List<UnmappedObject> findUnmappedInSchema(
            final DatabaseMetaData databaseMetaData, final String catalog,
            final String schema,
            final Map<String, Set<String>> mappedColumnsByTable,
            final Set<String> excludes) throws SQLException {
        final List<UnmappedObject> found = new ArrayList<>();
        try (ResultSet tables = databaseMetaData.getTables(catalog, schema,
                null, new String[] { "TABLE" })) {
            while (tables.next()) {
                final String tableName = tables.getString("TABLE_NAME");
                if (!mappedColumnsByTable.containsKey(canonical(tableName))
                        && !isExcludedTable(schema, tableName, excludes)) {
                    found.add(new UnmappedObject(
                            qualifiedName(schema, tableName), null, false));
                }
            }
        }
        try (ResultSet columns = databaseMetaData.getColumns(catalog, schema,
                null, null)) {
            while (columns.next()) {
                final String tableName = columns.getString("TABLE_NAME");
                final Set<String> mappedColumns =
                        mappedColumnsByTable.get(canonical(tableName));
                final String columnName = columns.getString("COLUMN_NAME");
                if (mappedColumns == null
                        || mappedColumns.contains(canonical(columnName))
                        || isExcludedColumn(schema, tableName, columnName,
                                excludes)) {
                    continue;
                }
                final boolean notNullWithoutDefault =
                        "NO".equals(columns.getString("IS_NULLABLE"))
                                && columns.getString("COLUMN_DEF") == null;
                found.add(new UnmappedObject(qualifiedName(schema, tableName),
                        columnName, notNullWithoutDefault));
            }
        }
        return found;
    }

    private boolean isExcludedTable(final String schema,
            final String tableName, final Set<String> excludes) {
        return excludes.contains(canonical(tableName)) || excludes
                .contains(canonical(qualifiedName(schema, tableName)));
    }

    private boolean isExcludedColumn(final String schema,
            final String tableName, final String columnName,
            final Set<String> excludes) {
        final String canonicalColumn = canonical(columnName);
        return excludes.contains(canonical(tableName) + "." + canonicalColumn)
                || excludes.contains(canonical(qualifiedName(schema, tableName))
                        + "." + canonicalColumn);
    }

    private static String qualifiedName(final String schema,
            final String name) {
        return schema.isEmpty() ? name : schema + "." + name;
    }

    private static @Nullable String canonical(
            final @Nullable String identifier) {
        return identifier == null ? null : identifier.toLowerCase(Locale.ROOT);
    }
}
