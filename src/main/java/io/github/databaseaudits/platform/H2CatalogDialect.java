package io.github.databaseaudits.platform;

/**
 * The {@link CatalogDialect} for H2 2.x, reading from its {@code information_schema}
 * (the 1.x layout differed).
 */
public final class H2CatalogDialect implements CatalogDialect {
    @Override
    public String indexCatalogSql() {
        return """
                SELECT ic.table_name        AS table_name,
                       ic.index_name        AS index_name,
                       (i.index_type_name = 'PRIMARY KEY'
                        OR i.index_type_name LIKE '%UNIQUE%') AS is_unique,
                       (i.index_type_name = 'PRIMARY KEY')    AS is_primary,
                       FALSE                AS is_partial,
                       ic.column_name       AS column_name
                FROM   information_schema.index_columns ic
                JOIN   information_schema.indexes i
                  ON   i.index_schema = ic.index_schema
                 AND   i.index_name   = ic.index_name
                 AND   i.table_name   = ic.table_name
                WHERE  ic.table_schema = ?
                  AND  i.index_type_name <> 'SPATIAL INDEX'
                ORDER  BY 1, 2, ic.ordinal_position
                """;
    }

    /**
     * Standard information_schema; constraint names are unique per schema on
     * H2, so the joins are exact.
     */
    @Override
    public String foreignKeysSql() {
        return """
                SELECT tc.table_name      AS table_name,
                       tc.constraint_name AS constraint_name,
                       ref_tc.table_name  AS referenced_table,
                       kcu.column_name    AS column_name
                FROM   information_schema.table_constraints tc
                JOIN   information_schema.key_column_usage kcu
                  ON   kcu.constraint_schema = tc.constraint_schema
                 AND   kcu.constraint_name   = tc.constraint_name
                 AND   kcu.table_name        = tc.table_name
                JOIN   information_schema.referential_constraints rc
                  ON   rc.constraint_schema = tc.constraint_schema
                 AND   rc.constraint_name   = tc.constraint_name
                LEFT   JOIN information_schema.table_constraints ref_tc
                  ON   ref_tc.constraint_schema = rc.unique_constraint_schema
                 AND   ref_tc.constraint_name   = rc.unique_constraint_name
                WHERE  tc.constraint_type = 'FOREIGN KEY'
                  AND  tc.table_schema = ?
                ORDER  BY 1, 2, kcu.ordinal_position
                """;
    }

    /**
     * Standard information_schema: {@code position_in_unique_constraint} maps
     * each FK column to the referenced unique/PK constraint's column at that
     * position. The declared type is composed from {@code data_type} plus the
     * character length for text types and, for the decimal family (radix 10),
     * the precision and scale — so {@code DECIMAL(10,2)} and {@code DECIMAL(5,0)}
     * render distinctly instead of both collapsing to a bare {@code NUMERIC}
     * (which would hide the mismatch that PostgreSQL's {@code format_type} and
     * MySQL's {@code column_type} both catch). The radix-2 integer types keep
     * their bare {@code data_type}, and {@code '(' || NULL || ')'} concatenates
     * to NULL so COALESCE drops the length where it does not apply.
     */
    @Override
    public String foreignKeyColumnTypesSql() {
        return """
                SELECT tc.table_name      AS table_name,
                       tc.constraint_name AS constraint_name,
                       kcu.column_name    AS column_name,
                       col.data_type || COALESCE('(' || col.character_maximum_length || ')', '')
                                     || CASE WHEN col.numeric_precision_radix = 10
                                             THEN '(' || col.numeric_precision || ',' || col.numeric_scale || ')'
                                             ELSE '' END AS column_type,
                       ref_kcu.table_name  AS referenced_table,
                       ref_kcu.column_name AS referenced_column,
                       rcol.data_type || COALESCE('(' || rcol.character_maximum_length || ')', '')
                                      || CASE WHEN rcol.numeric_precision_radix = 10
                                              THEN '(' || rcol.numeric_precision || ',' || rcol.numeric_scale || ')'
                                              ELSE '' END AS referenced_type
                FROM   information_schema.table_constraints tc
                JOIN   information_schema.key_column_usage kcu
                  ON   kcu.constraint_schema = tc.constraint_schema
                 AND   kcu.constraint_name   = tc.constraint_name
                 AND   kcu.table_name        = tc.table_name
                JOIN   information_schema.referential_constraints rc
                  ON   rc.constraint_schema = tc.constraint_schema
                 AND   rc.constraint_name   = tc.constraint_name
                JOIN   information_schema.key_column_usage ref_kcu
                  ON   ref_kcu.constraint_schema = rc.unique_constraint_schema
                 AND   ref_kcu.constraint_name   = rc.unique_constraint_name
                 AND   ref_kcu.ordinal_position  = kcu.position_in_unique_constraint
                JOIN   information_schema.columns col
                  ON   col.table_schema = kcu.table_schema
                 AND   col.table_name   = kcu.table_name
                 AND   col.column_name  = kcu.column_name
                JOIN   information_schema.columns rcol
                  ON   rcol.table_schema = ref_kcu.table_schema
                 AND   rcol.table_name   = ref_kcu.table_name
                 AND   rcol.column_name  = ref_kcu.column_name
                WHERE  tc.constraint_type = 'FOREIGN KEY'
                  AND  tc.table_schema = ?
                ORDER  BY 1, 2, kcu.ordinal_position
                """;
    }
}
