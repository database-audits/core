package io.github.databaseaudits.audit.jpa;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.hibernate.boot.Metadata;
import org.hibernate.boot.model.relational.Database;
import org.hibernate.dialect.Dialect;
import org.hibernate.mapping.Column;
import org.hibernate.mapping.Table;
import org.hibernate.type.descriptor.jdbc.JdbcType;
import org.hibernate.type.descriptor.jdbc.spi.JdbcTypeRegistry;
import org.jspecify.annotations.Nullable;

import io.github.databaseaudits.audit.finding.Finding;
import io.github.databaseaudits.audit.finding.SchemaColumnMissingFinding;
import io.github.databaseaudits.audit.finding.SchemaColumnTypeMismatchFinding;
import io.github.databaseaudits.audit.finding.SchemaTableMissingFinding;

import jakarta.persistence.EntityManagerFactory;

/**
 * JPA mapping audit — verifies that every mapped entity matches the schema and
 * reports <em>all</em> mismatches in one run.
 *
 * <p>
 * It walks Hibernate's fully resolved boot mapping model (captured by
 * {@link MappingMetadataIntegrator}) and, for each mapped physical table,
 * confirms against the live {@link DatabaseMetaData} that the table exists, each
 * mapped column exists, and each column's type is compatible — collecting a
 * violation for each problem rather than stopping at the first. Column-type
 * compatibility uses Hibernate's own rule (the same {@code equivalentTypes} /
 * type-name comparison its schema validator applies), so a mapping Hibernate
 * considers valid is never flagged.
 *
 * <p>
 * This deliberately does <em>not</em> rely on Hibernate's
 * {@code hibernate.hbm2ddl.auto=validate} startup validation: that fails fast on
 * the first mismatch and aborts the {@link EntityManagerFactory} build, so it can
 * only ever surface one problem per run. Run the context with
 * {@code ddl-auto=none} and let this audit enumerate the rest.
 *
 * <p>
 * Advisory where a mismatch is known and acceptable: pass it as an
 * {@code excludedRelation} to {@link #audit(Set)} rather than weakening the
 * audit.
 *
 * <p>
 * Fix: reconcile the entity mappings with the (Liquibase-built) schema —
 * whichever drifted.
 */
public class SchemaEntityValidationAudit {
    private final Supplier<@Nullable Metadata> metadataSupplier;
    private final DataSource dataSource;

    /**
     * Constructs the audit around the boot mapping model and the datasource whose
     * live schema it is checked against.
     *
     * @param metadataSupplier
     *                             supplies the boot {@link Metadata} for the
     *                             persistence unit, resolved when {@link #audit()}
     *                             runs; see
     *                             {@link #forEntityManagerFactory(EntityManagerFactory, DataSource)}.
     * @param dataSource
     *                             the datasource whose schema the mappings are
     *                             validated against.
     */
    public SchemaEntityValidationAudit(
            final Supplier<@Nullable Metadata> metadataSupplier,
            final DataSource dataSource) {
        this.metadataSupplier = metadataSupplier;
        this.dataSource = dataSource;
    }

    /**
     * Builds an audit for a JPA {@link EntityManagerFactory}, resolving its boot
     * {@link Metadata} from {@link MappingMetadataIntegrator} when the audit runs.
     *
     * @param entityManagerFactory
     *                                 the factory whose mappings to validate.
     * @param dataSource
     *                                 the datasource whose schema to validate
     *                                 against.
     * @return the audit.
     */
    public static SchemaEntityValidationAudit forEntityManagerFactory(
            final EntityManagerFactory entityManagerFactory,
            final DataSource dataSource) {
        return new SchemaEntityValidationAudit(
                () -> MappingMetadataIntegrator
                        .metadataFor(entityManagerFactory),
                dataSource);
    }

    /**
     * Validates every mapped physical table against the live schema.
     *
     * @return one violation per missing table, missing column, and incompatible
     *         column type; an empty list when the mappings match the schema.
     * @throws IllegalStateException
     *                                   if the boot mapping model was never
     *                                   captured (so the audit cannot run), or the
     *                                   database metadata cannot be read.
     */
    public List<Finding> audit() {
        return audit(Set.of());
    }

    /**
     * Validates every mapped physical table against the live schema, skipping the
     * excluded relations.
     *
     * @param excludedRelations
     *                              relations to skip, each a table name or a
     *                              {@code table.column} pair — optionally
     *                              schema-qualified ({@code schema.table} or
     *                              {@code schema.table.column}) to scope it to a
     *                              single schema — matched case-insensitively. Use
     *                              this to suppress a known, acceptable mismatch
     *                              instead of weakening the audit.
     * @return one violation per missing table, missing column, and incompatible
     *         column type among the non-excluded relations; an empty list when
     *         they match the schema.
     * @throws IllegalStateException
     *                                   if the boot mapping model was never
     *                                   captured (so the audit cannot run), or the
     *                                   database metadata cannot be read.
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
                .map(SchemaEntityValidationAudit::canonical)
                .collect(Collectors.toSet());
        final Database database = metadata.getDatabase();
        final Dialect dialect = database.getDialect();
        final JdbcTypeRegistry jdbcTypeRegistry =
                database.getTypeConfiguration().getJdbcTypeRegistry();

        final List<Finding> violations = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            final DatabaseMetaData databaseMetaData = connection.getMetaData();
            final String catalog = connection.getCatalog();
            final String defaultSchema = connection.getSchema();
            final Map<String, Map<String, Map<String, DatabaseColumn>>> columnsBySchema =
                    new HashMap<>();

            for (final Table table : metadata.collectTableMappings()) {
                if (!table.isPhysicalTable()) {
                    continue;
                }
                final String tableName = table.getName();
                final String schema =
                        table.getSchema() != null ? table.getSchema()
                                : defaultSchema;
                final String canonicalTable = canonical(tableName);
                final String canonicalQualifiedTable =
                        canonical(qualifiedName(schema, tableName));
                if (excludes.contains(canonicalTable)
                        || excludes.contains(canonicalQualifiedTable)) {
                    continue;
                }
                final Map<String, Map<String, DatabaseColumn>> tablesInSchema =
                        columnsBySchema.computeIfAbsent(schema,
                                s -> readColumns(databaseMetaData, catalog, s));
                final Map<String, DatabaseColumn> databaseColumns =
                        tablesInSchema.get(canonicalTable);
                if (databaseColumns == null) {
                    violations.add(new SchemaTableMissingFinding(
                            qualifiedName(schema, tableName)));
                    continue;
                }
                for (final Column column : table.getColumns()) {
                    final String columnName = column.getName();
                    final String canonicalColumn = canonical(columnName);
                    if (excludes.contains(canonicalTable + "." + canonicalColumn)
                            || excludes.contains(canonicalQualifiedTable + "."
                                    + canonicalColumn)) {
                        continue;
                    }
                    final DatabaseColumn databaseColumn =
                            databaseColumns.get(canonicalColumn);
                    if (databaseColumn == null) {
                        violations.add(new SchemaColumnMissingFinding(
                                qualifiedName(schema, tableName), columnName,
                                column.getSqlType(metadata)
                                        .toLowerCase(Locale.ROOT)));
                    } else if (!hasMatchingType(column, databaseColumn, metadata,
                            dialect, jdbcTypeRegistry)) {
                        violations.add(new SchemaColumnTypeMismatchFinding(
                                qualifiedName(schema, tableName), columnName,
                                databaseColumn.typeName()
                                        .toLowerCase(Locale.ROOT),
                                column.getSqlType(metadata)
                                        .toLowerCase(Locale.ROOT)));
                    }
                }
            }
        } catch (final SQLException e) {
            throw new IllegalStateException(
                    "Failed to read database metadata for JPA entity/schema validation.",
                    e);
        }
        return violations;
    }

    private static Map<String, Map<String, DatabaseColumn>> readColumns(
            final DatabaseMetaData databaseMetaData, final String catalog,
            final String schema) {
        final Map<String, Map<String, DatabaseColumn>> tables = new HashMap<>();
        try (ResultSet columns =
                databaseMetaData.getColumns(catalog, schema, null, null)) {
            while (columns.next()) {
                final DatabaseColumn databaseColumn = new DatabaseColumn(
                        columns.getString("TYPE_NAME"),
                        columns.getInt("DATA_TYPE"),
                        columns.getInt("COLUMN_SIZE"),
                        columns.getInt("DECIMAL_DIGITS"));
                tables.computeIfAbsent(
                        canonical(columns.getString("TABLE_NAME")),
                        table -> new HashMap<>()).put(
                                canonical(columns.getString("COLUMN_NAME")),
                                databaseColumn);
            }
        } catch (final SQLException e) {
            throw new IllegalStateException(
                    "Failed to read columns for schema [" + schema + "].", e);
        }
        return tables;
    }

    /**
     * Reproduces {@code org.hibernate.tool.schema.internal.ColumnDefinitions#hasMatchingType}:
     * a mapped column matches when the dialect deems the JDBC type codes
     * equivalent or the (argument-stripped, normalized) type names agree, falling
     * back to re-resolving the database type name through the dialect.
     */
    private static boolean hasMatchingType(final Column column,
            final DatabaseColumn databaseColumn, final Metadata metadata,
            final Dialect dialect, final JdbcTypeRegistry jdbcTypeRegistry) {
        final int mappedTypeCode = column.getSqlTypeCode(metadata);
        final boolean typesMatch =
                dialect.equivalentTypes(mappedTypeCode, databaseColumn.typeCode())
                        || normalize(stripArguments(column.getSqlType(metadata)))
                                .equals(normalize(databaseColumn.typeName()));
        if (typesMatch) {
            return true;
        }
        final JdbcType resolved = dialect.resolveSqlTypeDescriptor(
                databaseColumn.typeName(), databaseColumn.typeCode(),
                databaseColumn.columnSize(), databaseColumn.decimalDigits(),
                jdbcTypeRegistry);
        return dialect.equivalentTypes(mappedTypeCode,
                resolved.getDefaultSqlTypeCode());
    }

    private static @Nullable String normalize(final @Nullable String typeName) {
        if (typeName == null) {
            return null;
        }
        final String lowercase = typeName.toLowerCase(Locale.ROOT);
        return switch (lowercase) {
            case "int" -> "integer";
            case "character" -> "char";
            case "character varying" -> "varchar";
            case "binary varying" -> "varbinary";
            case "character large object" -> "clob";
            case "binary large object" -> "blob";
            case "interval second" -> "interval";
            case "double precision" -> "double";
            default -> lowercase;
        };
    }

    private static @Nullable String stripArguments(
            final @Nullable String typeExpression) {
        if (typeExpression == null) {
            return null;
        }
        final int parenthesis = typeExpression.indexOf('(');
        return parenthesis > 0 ? typeExpression.substring(0, parenthesis).trim()
                : typeExpression;
    }

    private static String qualifiedName(final String schema, final String name) {
        return schema == null ? name : schema + "." + name;
    }

    private static @Nullable String canonical(final @Nullable String identifier) {
        return identifier == null ? null : identifier.toLowerCase(Locale.ROOT);
    }

    private record DatabaseColumn(String typeName, int typeCode, int columnSize,
            int decimalDigits) {
    }
}
