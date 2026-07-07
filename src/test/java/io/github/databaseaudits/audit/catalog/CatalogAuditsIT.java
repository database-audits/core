package io.github.databaseaudits.audit.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.BeforeParameterizedClassInvocation;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.EnumSource;

import io.github.databaseaudits.audit.finding.Finding;
import io.github.databaseaudits.catalog.ForeignKeyCatalog;
import io.github.databaseaudits.catalog.IndexCatalog;
import io.github.databaseaudits.fixture.DatabaseFixture;
import io.github.databaseaudits.jdbc.CatalogQueries;
import io.github.databaseaudits.platform.DatabasePlatform;

/**
 * End-to-end run of every catalog-driven audit against every supported database
 * — the whole class runs once per {@link DatabaseFixture}: real catalog SQL
 * against a real catalog on PostgreSQL, MySQL, and MariaDB (throwaway
 * containers; Docker required — these fail rather than skip without it) and
 * embedded in-memory H2. The fixture plants every auditable catalog violation,
 * and each test passes only when its audit reports the planted violation — and
 * passes once the violation is excluded — so a vacuously green audit cannot
 * slip through.
 */
@ParameterizedClass(name = "{0}")
@EnumSource(DatabaseFixture.class)
class CatalogAuditsIT {
    @Parameter
    private DatabaseFixture fixture;

    @BeforeParameterizedClassInvocation
    static void createViolationSchema(final DatabaseFixture fixture)
            throws SQLException {
        fixture.createViolationSchema();
    }

    private CatalogQueries catalogQueries() {
        return new CatalogQueries(fixture.dataSource());
    }

    private IndexCatalog indexCatalog() {
        return new IndexCatalog(catalogQueries(), fixture.platform());
    }

    private ForeignKeyCatalog foreignKeyCatalog() {
        return new ForeignKeyCatalog(catalogQueries(), fixture.platform());
    }

    @Test
    void testFromDataSource_FixtureDataSource_DetectsPlatform() {
        assertThat(DatabasePlatform.fromDataSource(fixture.dataSource()))
                .as("DataSource should detect the fixture platform.")
                .isEqualTo(fixture.platform());
    }

    @Test
    void testPrimaryKeyPresenceAudit_PkLessTable_ReportedThenEmptyWhenExcluded() {
        final var audit =
                new PrimaryKeyPresenceAudit(catalogQueries(), fixture.platform());
        final String noPkTable = fixture.expectedIdentifier("no_pk_table");

        assertThat(audit.audit(fixture.schema(), Set.of()))
                .extracting(Finding::description)
                .as("Table without primary key should be reported.")
                .contains(noPkTable);
        assertThat(audit.audit(fixture.schema(), Set.of(noPkTable)))
                .as("Excluding the table should produce no violations.")
                .isEmpty();
    }

    @Test
    void testForeignKeyNotNullAudit_NullableFkColumn_ReportedThenEmptyWhenExcluded() {
        final var audit =
                new ForeignKeyNotNullAudit(catalogQueries(), fixture.platform());
        final String nullableFkColumn = fixture.expectedIdentifier("child")
                + "." + fixture.expectedIdentifier("optional_parent_id");

        assertThat(audit.audit(fixture.schema(), Set.of()))
                .as("Nullable FK column should be reported.")
                .anySatisfy(
                        violation -> assertThat(violation.description()).contains(nullableFkColumn));
        assertThat(audit.audit(fixture.schema(), Set.of(nullableFkColumn)))
                .as("Excluding the column should produce no violations.")
                .isEmpty();
    }

    @Test
    void testRedundantIndexAudit_LeadingPrefixDuplicate_ReportedThenEmptyWhenExcluded() {
        final var audit = new RedundantIndexAudit(indexCatalog());
        final String redundantIndex =
                fixture.expectedIdentifier("idx_child_created");
        final String coveringIndex =
                fixture.expectedIdentifier("idx_child_created_id");

        assertThat(audit.audit(fixture.schema(), Set.of()))
                .as("Leading-prefix duplicate index should be reported.")
                .anySatisfy(violation -> assertThat(violation.description()).contains(
                        redundantIndex + " is covered by " + coveringIndex));
        assertThat(audit.audit(fixture.schema(), Set.of(redundantIndex)))
                .as("Excluding the redundant index should produce no violations.")
                .isEmpty();
    }

    /**
     * The planted mismatch differs per platform — {@code parent_ref INTEGER}
     * referencing the BIGINT {@code parent(id)} (H2, PostgreSQL),
     * {@code code_ref VARCHAR(20)} referencing {@code parent.code VARCHAR(10)}
     * (MySQL/MariaDB, which reject integer-width FK mismatches) — so the
     * expected column comes from the fixture.
     */
    @Test
    void testForeignKeyTypeMatchAudit_MismatchedFkColumnType_ReportedThenEmptyWhenExcluded() {
        final var audit =
                new ForeignKeyTypeMatchAudit(catalogQueries(), fixture.platform());
        final String mismatchedColumn = fixture
                .expectedIdentifier("type_mismatch_child") + "."
                + fixture.expectedIdentifier(fixture.mismatchedFkColumn());

        assertThat(audit.audit(fixture.schema(), Set.of()))
                .as("Mismatched FK column type should be reported.")
                .anySatisfy(violation -> assertThat(violation.description())
                        .contains(mismatchedColumn)
                        .contains(fixture
                                .expectedIdentifier("fk_type_mismatch_parent")))
                .noneSatisfy(violation -> assertThat(violation.description()).contains(
                        fixture.expectedIdentifier("fk_child_parent")))
                .noneSatisfy(violation -> assertThat(violation.description()).contains(
                        fixture.expectedIdentifier("fk_child_optional")));
        assertThat(audit.audit(fixture.schema(), Set.of(mismatchedColumn)))
                .as("Excluding the mismatched column should produce no violations.")
                .isEmpty();
    }

    /**
     * The fixture leaves {@code fk_unindexed_child_parent} without a supporting
     * index wherever the platform permits that violation to exist (PostgreSQL,
     * MariaDB); H2 and MySQL auto-create an FK index that cannot be dropped, so
     * there the audit's pass <em>is</em> the platform's guarantee.
     * No exclusions are passed initially so the properly indexed FKs prove the
     * audit reports only the planted one; then excluding the constraint proves the
     * exclusion path.
     */
    @Test
    void testForeignKeyIndexAudit_PlantedUnindexedFk_ReportedWherePlantedThenEmptyWhenExcluded() {
        final var audit = new ForeignKeyIndexAudit(catalogQueries(),
                indexCatalog(), fixture.platform());
        final String unindexedConstraint =
                fixture.expectedIdentifier("fk_unindexed_child_parent");

        if (fixture.fkIndexViolationPlanted()) {
            assertThat(audit.audit(fixture.schema(), Set.of()))
                    .as("Planted unindexed FK should be reported.")
                    .anySatisfy(violation -> assertThat(violation.description())
                            .contains(fixture
                                    .expectedIdentifier("unindexed_fk_child"))
                            .contains(unindexedConstraint))
                    .noneSatisfy(violation -> assertThat(violation.description()).contains(
                            fixture.expectedIdentifier("fk_child_parent")))
                    .noneSatisfy(violation -> assertThat(violation.description()).contains(
                            fixture.expectedIdentifier("fk_child_optional")));
            assertThat(audit.audit(fixture.schema(), Set.of(unindexedConstraint)))
                    .as("Excluding the planted constraint should produce no violations.")
                    .isEmpty();
        } else {
            assertThat(audit.audit(fixture.schema(), Set.of()))
                    .as("No FK index violations expected on this platform.")
                    .isEmpty();
        }
    }

    @Test
    void testDuplicateForeignKeyAudit_DuplicateFkPair_ReportedThenEmptyWhenExcluded() {
        final var audit = new DuplicateForeignKeyAudit(foreignKeyCatalog());
        final String duplicateConstraint =
                fixture.expectedIdentifier("fk_duplicate_child_parent");
        final String duplicateConstraint2 =
                fixture.expectedIdentifier("fk_duplicate_child_parent_2");

        assertThat(audit.audit(fixture.schema(), Set.of()))
                .as("The duplicate FK pair should be reported, naming both constraints.")
                .anySatisfy(violation -> assertThat(violation.description())
                        .contains(fixture
                                .expectedIdentifier("duplicate_fk_child"))
                        .contains(duplicateConstraint)
                        .contains(duplicateConstraint2))
                .noneSatisfy(violation -> assertThat(violation.description()).contains(
                        fixture.expectedIdentifier("fk_child_parent")));
        assertThat(audit.audit(fixture.schema(), Set.of(duplicateConstraint2)))
                .as("Excluding one of the duplicate pair should produce no violations.")
                .isEmpty();
    }

    @Test
    void testPrimaryKeyTypeAudit_IntegerPk_ReportedThenEmptyWhenExcluded() {
        final var audit =
                new PrimaryKeyTypeAudit(catalogQueries(), fixture.platform());
        final String narrowPkTable =
                fixture.expectedIdentifier("narrow_pk_table");
        final String narrowPkColumn =
                narrowPkTable + "." + fixture.expectedIdentifier("id");

        assertThat(audit.audit(fixture.schema(), Set.of()))
                .as("The narrow integer primary key should be reported.")
                .anySatisfy(violation -> assertThat(violation.description())
                        .contains(narrowPkTable))
                .noneSatisfy(violation -> assertThat(violation.description()).contains(
                        fixture.expectedIdentifier("parent")));
        assertThat(audit.audit(fixture.schema(), Set.of(narrowPkColumn)))
                .as("Excluding the narrow primary key column should produce no violations.")
                .isEmpty();
    }

    @Test
    void testUniqueIndexNotNullAudit_UniqueIndexOnNullableColumn_ReportedThenEmptyWhenExcluded() {
        final var audit = new UniqueIndexNotNullAudit(catalogQueries(),
                indexCatalog(), fixture.platform());
        final String optionalCodeIndex =
                fixture.expectedIdentifier("uq_optional_code");

        assertThat(audit.audit(fixture.schema(), Set.of()))
                .as("The UNIQUE index over a nullable column should be reported.")
                .anySatisfy(violation -> assertThat(violation.description())
                        .contains(fixture
                                .expectedIdentifier("optional_code_table"))
                        .contains(optionalCodeIndex)
                        .contains(fixture.expectedIdentifier("code")))
                .noneSatisfy(violation -> assertThat(violation.description()).contains(
                        fixture.expectedIdentifier("uq_parent_code")));
        assertThat(audit.audit(fixture.schema(), Set.of(optionalCodeIndex)))
                .as("Excluding the index should produce no violations.")
                .isEmpty();
    }
}
