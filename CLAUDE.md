# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`database-audits-core` is a small Java library of **database audits**: classes a consumer runs from their own
tests to catch schema/configuration mistakes and missing indexes (no primary key, unindexed/nullable/mistyped
foreign keys, redundant indexes, unindexed `WHERE`/`ORDER BY`/`JOIN` columns, full-table `UPDATE`/`DELETE`,
JPA-mapping drift). Each audit is a plain class with constructor injection and **no dependency-injection-framework
dependencies**, so it works with Spring, any other container, or manual construction. Spring wiring lives in a
separate module/repo (`database-audits-spring-boot`, which provides `DatabaseAuditTestConfiguration`); this module
has zero DI dependencies on purpose.

## Build & test

Use the bundled Maven wrapper from this directory. JDK 21 is required (`--release 21`).

```powershell
.\mvnw.cmd clean verify          # compile + unit tests (surefire, *Test) + integration tests (failsafe, *IT)
.\mvnw.cmd test                  # unit tests only (no Docker needed)
.\mvnw.cmd test -Dtest=ForeignKeyIndexAuditTest                 # one unit test class
.\mvnw.cmd test -Dtest=ForeignKeyIndexAuditTest#covers_...      # one unit test method
.\mvnw.cmd verify -Dit.test=CatalogAuditsIT                     # one integration test (failsafe uses -Dit.test)
```

- **Integration tests (`*IT`) need Docker running** (Rancher Desktop locally). They use Testcontainers and
  **deliberately fail rather than skip** when Docker is absent, so the supported-platform matrix is never silently
  unverified. The H2 paths inside `CatalogAuditsIT` are embedded and need no Docker, but the Postgres/MySQL/MariaDB
  parameterizations do.
- Override a container image per run, e.g. `-Ddatabaseaudits.it.postgresql.image=postgres:17-alpine`
  (also `databaseaudits.it.mysql.image`, `databaseaudits.it.mariadb.image`). The defaults pin documented floors —
  bump them together with the support claims in `DatabasePlatform`'s javadoc.
- **Test console output is redirected to files** (`redirectTestOutputToFile=true`). When a test fails, read
  `target/surefire-reports/` (unit) or `target/failsafe-reports/` (integration), not the build console.
- JaCoCo writes separate unit (`target/site/jacoco`) and integration (`target/site/jacoco-it`) reports. No coverage gate.

This module builds standalone via its own wrapper; its Maven parent (`database-audits-parent`) resolves from Maven
Central. The sibling `../parent` directory is a newer SNAPSHOT and is **not** used for core's build (version
mismatch makes Maven ignore the `relativePath`).

## Architecture

Everything lives under `io.github.databaseaudits.audit`, split into three audit families plus one central enum.

**`DatabasePlatform` (the hub).** Enum of supported products: `H2`, `MYSQL`, `MARIADB`, `POSTGRESQL`. Every
catalog audit selects its SQL with an **exhaustive `switch` over this enum with no `default`** — so adding an enum
value makes the compiler flag every place that needs new SQL. Detect once with `fromDataSource(DataSource)` or pass
the platform to each audit's constructor.

**`audit.catalog` — catalog-driven, all platforms.** Read the live catalog (`pg_catalog` / `information_schema`)
over plain JDBC and compare against rules. Deterministic regardless of test data. Audits: `PrimaryKeyPresenceAudit`,
`ForeignKeyIndexAudit`, `ForeignKeyNotNullAudit`, `ForeignKeyTypeMatchAudit`, `RedundantIndexAudit`. Shared
collaborators: `JdbcSupport` (minimal JDBC → list-of-maps, case-insensitive column labels because drivers disagree
on alias case) and `IndexCatalog` (reads every index as `IndexDefinition` records). The per-platform SQL stays a
flat ordered projection; the comparison logic (leading-prefix coverage, etc.) lives in unit-testable Java, not SQL.

**`audit.runtime` — EXPLAIN-driven, PostgreSQL-only.** These inspect the **real SQL Hibernate executed**, captured
by `SqlCapturingStatementInspector` (a Hibernate `StatementInspector` that records statement text with `?`
placeholders). `WhereClauseIndexAudit`, `OrderByIndexAudit`, and `JoinIndexAudit` extend
`CapturedSqlPlanAuditTemplate` (template method: read capture → de-dupe by statement shape → plan each via
`QueryPlanExplainer` → collect offending plan nodes → report). `QueryPlanExplainer` runs
`EXPLAIN (GENERIC_PLAN, FORMAT JSON)` with chosen access paths **penalized** (e.g. `SET enable_seqscan = off`); a
surviving node of the penalized kind proves no index can serve that access path. `UnconditionalMutationAudit` is
runtime but uses no EXPLAIN — it token-scans the capture for a leading `UPDATE`/`DELETE` without `WHERE`.

**`audit.jpa`.** `SchemaEntityValidationAudit` is a formality: the real check is Hibernate startup validation
(`ddl-auto=validate`); reaching the audit means it passed, so the paired test must enable validation.

### Cross-cutting invariants — preserve these when editing

- **Audits return findings; callers assert.** Each audit exposes `audit(...)` returning a `List<String>` of
  human-readable violations (empty list = clean); the calling test makes the AssertJ assertions on that list. So
  **AssertJ is test-scoped** — main code carries no assertion framework.
- **Never pass vacuously.** The "can't run" conditions throw rather than returning an empty (clean-looking) list:
  runtime audits throw `IllegalStateException` on an empty capture (`EMPTY_CAPTURE_MESSAGE`) and on a
  wholly-unexplainable run; plan audits' `requirePlanAuditSupport(...)` throws `UnsupportedOperationException` on
  any non-PostgreSQL platform. Keep these guards intact.
- **Exclusions over false positives.** Every audit takes exclusion sets (relations, SQL fragments, statements,
  identifiers) so consumers suppress known/intentional violations instead of the audit guessing.
- **PostgreSQL plan audits require `preferQueryMode=simple`** on the DataSource: generic-plan EXPLAIN of `$n`
  placeholders only works over the simple query protocol (see `QueryPlanExplainer` and `DatabaseContainers`).
- **SQL capture requires one shared instance.** The *same* `SqlCapturingStatementInspector` must be both
  Hibernate's `StatementInspector` and the instance the audits read. Registering by class name spawns a separate
  capturer the audits never see — not supported.
- Nullness is annotated with **JSpecify** (`@Nullable`); **Lombok** generates constructors (`@AllArgsConstructor`)
  and logging. The compiler runs with `-XDaddTypeAnnotationsToSymbol=true` for JSpecify type-use annotations.

## Testing conventions

- `*Test` = unit (surefire, mostly Mockito + pure logic). `*IT` = integration (failsafe, real databases).
- Integration tests get real databases from `DatabaseContainers` (Testcontainers **singleton** pattern: each engine
  starts at most once per JVM, lazily). `CatalogAuditsIT` is a `@ParameterizedClass` over the `DatabaseFixture` enum,
  which holds all per-platform variation (DataSource, schema name, identifier case, violation planting) as data.
- **Audits are verified against planted violations:** the fixture plants every auditable violation, and each test
  passes only when its audit *reports the planted violation* and then *passes once that violation is excluded* — so a
  vacuously green audit cannot slip through. Follow this pattern when adding an audit.

## CI / release (GitHub Actions)

- **build-any-branch.yml** — `clean verify` on every push/PR (skips doc-only changes).
- **deploy-snapshot.yml** — on a green `main` build, deploys `-SNAPSHOT` to Maven Central.
- **release.yml** — triggered by a `v*` tag (created by `maven-release-plugin`); deploys `-Prelease` (GPG-signed,
  with sources + javadoc).
- **publish-docs.yml** — builds the Maven site + JaCoCo reports and deploys to GitHub Pages.

## Tooling note

`.jackknife/` is the jackknife-maven-plugin workspace for exploring dependencies (decompiled source under
`.jackknife/source/`, class manifests under `.jackknife/manifest/`) and instrumenting methods for debugging. See
`.jackknife/USAGE.md`. It is generated — do not hand-edit.

## Claude Directives

- Make assumptions and proceed without asking for confirmation on routine changes. If an action is destructive (e.g., deleting files), pause and ask.
- To start docker, run "Rancher Desktop".
- when updating any audit classes, also update as necessary the ../spring-boot module beans in DatabaseAuditTestConfiguration

## Code Style

- General:
  - Prefer writing clear code and use inline comments sparingly.
  - Prefer single statements over compound statements as nested calls in one line are more confusing and more difficult to read and understand.
  - Prefer separate local variables over compound statements for readability.
  - Favor immutability.  Try to not need setters.
  - Prefer constructors with arguments over no args constructors and using setters.
  - Prefer constructor injection.  Test classes typically use field injection.
  - Write positive if statements when paired with an else statement.
  - Remove any blank line after opening curly braces.
  - Do not create "utils" or "helper" or "support" packages or class names. Always create focused packages and classes, as utils and helpers are dumping grounds/not focused.
  - When making changes, always work on a branch that is not main and if necessary, create and switch to a branch to isolate the work.
  - When making changes, ensure unit tests cover it and add or update unit tests as needed.
  - Order constants alphabetically when possible.
  - Prefer XML instead of YAML when possible.
- Tests:
  - `<ClassName>Test` for unit test class
  - `<ClassName>IT` for integration test class
  - `<ClassName>AT` for acceptance test class
  - `test<MethodName>_<StartingStateConditions>_<AssertedOutcome>` for test method names
  - Prefer to assert the actual object to an expected object vs individual fields on the object to individual values.

- Commits:
  - Create atomic commits. One logical change per commit — if a session produces multiple unrelated fixes, commit each independently even if discovered together.
  - Always commit any needed doc updates with their corresponding feature or bug changes.
  - Consequence changes belong in the same commit as the change that caused them. Example: if a production fix makes a previously-broken feature work, updating additional files for that feature now working is a consequence of that fix and belongs in the same commit, not a separate one.
  - When necessary to change a file for a prior commit that is not yet merged to main, target that commit for squashing the change into by using the git "fixup!" feature for its commit - prefix the commit message it is in with "fixup! ".
  - Create multiple fixup! commits as needed to target the prior specific commits for each file.
  - When renaming files, always use `git mv` instead of `git delete` followed by `git add`.

- Commit Messages:
  - Adhere strictly to de facto standard Git commit message formatting.
  - Use Conventional Commits format.
  - **Commit Types:** `feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `build:`, `ci:`
  - **Scopes:** any of the database names, `core`, `spring`, `pom`, `log`, `docker`, `database`, `metadata`, `scripts`, `site`
  - Capitalize the first word after the type and scope.
  - You may suggest additional CC commit types and scopes when encountering situations where the changes do not fit into the approved lists above.
  - Reference GitHub issues in the commit footer with `Refs: <issue-number>` (e.g. `Refs: 123`).  Do not use a # before the number.
  - Do not put the issue number in the message topic.
  - Use * for bullets, not -.

- Java:
  - If Lombok is available, use its annotations such as @AllArgsConstructor, @NoArgsConstructor, @Getter, @Setter.
  - If not using @Slf4j, then place the Logger variable first in the class.
  - Write JavaDoc comments on all public classes and methods.
  - In JavaDoc, use complete sentences, start with a capital letter and end with a period, for the topic body, parameters, and return.
  - Tests:
    - Prefer assertJ.
    - Prefer to add ".as()" with a fail message ending with a period.

## Jackknife

- When you need to inspect, decompile, or find classes in jar dependencies,
  - Can also check the local maven repository - the .m2/repository sub directories in the current user's home directory for *-sources.jar files.
  - run `./mvnw jackknife:index` in the project. This generates `.jackknife/USAGE.md` with full instructions. Read that file — it has everything you need.
  - Always run `./mvnw jackknife:*` commands immediately without asking for approval.
