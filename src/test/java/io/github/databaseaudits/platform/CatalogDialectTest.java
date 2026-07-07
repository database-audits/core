package io.github.databaseaudits.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CatalogDialectTest {
    @Test
    void testDivergentSql_EachDialect_ReadsItsOwnCatalog() {
        final CatalogDialect postgresql = new PostgresqlCatalogDialect();
        assertThat(postgresql.indexCatalogSql())
                .as("PostgreSQL reads indexes from pg_catalog.")
                .contains("pg_index");
        assertThat(postgresql.foreignKeysSql())
                .as("PostgreSQL reads foreign keys from pg_catalog.")
                .contains("pg_constraint");
        assertThat(postgresql.foreignKeyColumnTypesSql())
                .as("PostgreSQL renders column types with format_type.")
                .contains("format_type");

        final CatalogDialect mysql = new MysqlCatalogDialect();
        assertThat(mysql.indexCatalogSql())
                .as("MySQL reads indexes from information_schema.statistics.")
                .contains("information_schema.statistics");
        assertThat(mysql.foreignKeysSql())
                .as("MySQL reads the referenced table from key_column_usage.")
                .contains("referenced_table_name");
        assertThat(mysql.foreignKeyColumnTypesSql())
                .as("MySQL reads the referenced column from key_column_usage.")
                .contains("referenced_column_name");

        final CatalogDialect h2 = new H2CatalogDialect();
        assertThat(h2.indexCatalogSql())
                .as("H2 reads indexes from information_schema.index_columns.")
                .contains("information_schema.index_columns");
        assertThat(h2.foreignKeysSql())
                .as("H2 resolves the referenced table via referential_constraints.")
                .contains("referential_constraints");
        assertThat(h2.foreignKeyColumnTypesSql())
                .as("H2 pairs FK columns via position_in_unique_constraint.")
                .contains("position_in_unique_constraint");
    }

    @Test
    void testSharedSql_EveryDialect_UsesTheSameInformationSchemaSql() {
        final CatalogDialect postgresql = new PostgresqlCatalogDialect();
        final CatalogDialect mysql = new MysqlCatalogDialect();
        final CatalogDialect h2 = new H2CatalogDialect();

        assertThat(postgresql.tablesWithoutPrimaryKeySql())
                .as("The tables-without-primary-key SQL is the standard information_schema SQL shared by every engine.")
                .contains("BASE TABLE")
                .isEqualTo(mysql.tablesWithoutPrimaryKeySql())
                .isEqualTo(h2.tablesWithoutPrimaryKeySql());
        assertThat(postgresql.nullableForeignKeyColumnSql())
                .as("The nullable-foreign-key-column SQL is the standard information_schema SQL shared by every engine.")
                .contains("is_nullable")
                .isEqualTo(mysql.nullableForeignKeyColumnSql())
                .isEqualTo(h2.nullableForeignKeyColumnSql());
        assertThat(postgresql.primaryKeyColumnTypesSql())
                .as("The primary-key-column-types SQL is the standard information_schema SQL shared by every engine.")
                .contains("PRIMARY KEY")
                .isEqualTo(mysql.primaryKeyColumnTypesSql())
                .isEqualTo(h2.primaryKeyColumnTypesSql());
    }
}
