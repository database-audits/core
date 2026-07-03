package io.github.databaseaudits.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

class DatabasePlatformTest {
    @Test
    void testFromProductName_EverySupportedProductName_MapsToItsPlatform() {
        assertThat(DatabasePlatform.fromProductName("PostgreSQL"))
                .isEqualTo(DatabasePlatform.POSTGRESQL);
        assertThat(DatabasePlatform.fromProductName("MySQL"))
                .isEqualTo(DatabasePlatform.MYSQL);
        assertThat(DatabasePlatform.fromProductName("MariaDB"))
                .isEqualTo(DatabasePlatform.MARIADB);
        assertThat(DatabasePlatform.fromProductName("H2"))
                .isEqualTo(DatabasePlatform.H2);
    }

    @Test
    void testFromProductName_MixedCaseAndVendorDecorations_MatchesToPlatform() {
        assertThat(DatabasePlatform.fromProductName("postgresql"))
                .isEqualTo(DatabasePlatform.POSTGRESQL);
        assertThat(DatabasePlatform.fromProductName("MySQL Community Server"))
                .isEqualTo(DatabasePlatform.MYSQL);
    }

    @Test
    void testFromProductName_UnsupportedProduct_ThrowsNamingProductAndSupportedPlatforms() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DatabasePlatform.fromProductName("Oracle"))
                .withMessageContaining("Oracle")
                .withMessageContaining("PostgreSQL");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DatabasePlatform.fromProductName(null));
    }

    @Test
    void testFromDataSource_MetadataReportsProductName_DetectsPlatform()
            throws SQLException {
        final DataSource dataSource = dataSourceReporting("PostgreSQL");

        assertThat(DatabasePlatform.fromDataSource(dataSource))
                .isEqualTo(DatabasePlatform.POSTGRESQL);
    }

    @Test
    void testFromDataSource_ConnectionFailure_ThrowsIllegalState()
            throws SQLException {
        final DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("boom"));

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> DatabasePlatform.fromDataSource(dataSource))
                .withMessageContaining("product name");
    }

    @Test
    void testCatalogDialect_EachPlatform_HoldsItsDialectType() {
        assertThat(DatabasePlatform.POSTGRESQL.catalogDialect())
                .as("PostgreSQL uses its own catalog dialect.")
                .isInstanceOf(PostgresqlCatalogDialect.class);
        assertThat(DatabasePlatform.MYSQL.catalogDialect())
                .as("MySQL uses its own catalog dialect.")
                .isInstanceOf(MysqlCatalogDialect.class);
        assertThat(DatabasePlatform.MARIADB.catalogDialect())
                .as("MariaDB reuses the MySQL catalog dialect.")
                .isInstanceOf(MysqlCatalogDialect.class);
        assertThat(DatabasePlatform.H2.catalogDialect())
                .as("H2 uses its own catalog dialect.")
                .isInstanceOf(H2CatalogDialect.class);
    }

    private static DataSource dataSourceReporting(final String productName)
            throws SQLException {
        final DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(metaData.getDatabaseProductName()).thenReturn(productName);
        final Connection connection = mock(Connection.class);
        when(connection.getMetaData()).thenReturn(metaData);
        final DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        return dataSource;
    }
}
