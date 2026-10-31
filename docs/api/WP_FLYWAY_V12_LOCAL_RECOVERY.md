# WP Flyway V12: MySQL / MariaDB compatibility and local recovery

## Scope and migration identity

The LocalWP database is MySQL 8.0.35, even though Java connects through the MariaDB
JDBC driver. MySQL rejects `ADD COLUMN IF NOT EXISTS` in the legacy MariaDB V12.
A failed V12 prevents Spring startup; neither a browser refresh nor a product sync
can repair it. Swagger does not execute migrations independently of application startup.

`WpFlywayMigrationConfiguration` customizes the canonical
`classpath:db/wp/migration` location using the **WP server identity**, read with
`SELECT VERSION(), @@version_comment` (10-second query timeout). JDBC product name
alone is insufficient: Connector/J `useMysqlMetadata=true` labels MariaDB as MySQL.
MariaDB markers in the server version/comment select the original SQL V12; ordinary
MySQL 5.x/8.x/9.x selects Java V12. Unknown/masked versions fail closed instead of
guessing or repairing history. It does not use the JDBC URL as the engine detector and never uses
the Folio datasource. Noncanonical migration locations are not modified.

- Common SQL migrations V1–V11 and V13 onward remain in `db/wp/migration`.
- MariaDB additionally resolves `db/wp/vendor/mariadb`. V12 has the same filename,
  SQL bytes, description, version, SQL type and checksum (`-1886659825`) as before.
  Moving the resource does not require a checksum repair. Do not repair production
  merely because the resource location changed.
- MySQL resolves one explicit Java V12 (`config.migration.V12__folio_product_analytics_schema_v4`),
  checksum `2026091901`, JDBC type. It validates all existing barcode columns and the
  index before adding absent objects; incompatible definitions fail with
  `WP_V12_SCHEMA_MISMATCH`. It does not overwrite business values or repair history.
- The two V12 implementations must never be supplied to the same Flyway instance.
  A migration-only runner must use the same configuration customizer as Spring.
  Do not manually use just the common directory or both vendor implementations.

MySQL and MariaDB V12 history entries intentionally have different implementation
types/checksums. Copying an already-migrated database between engines requires an
explicit cross-engine migration plan, not blind `repair`. Versioned implementations
must remain immutable after application.

## Safe recovery of a failed local V12

There is **no automatic repair** in application startup. Recovery is an operator action.

1. Confirm the actual local DB/port, deployed artifact and migration set. Stop the
   local Java restart loop and local WP writers. Never infer locality from `.env` names.
2. Capture all Flyway history and schema definitions. Check V1–V11, both metric
   tables, `maximum_stock`, any `primary_barcode` columns, barcode index definition,
   and any V13–V15 objects. A failed history entry does not prove DDL was rolled back.
3. Take a full private backup, including data, routines, events, triggers and history.
   For all-InnoDB tables use a consistent transaction snapshot without concurrent DDL.
   Restore on an isolated same-version server; disable events/application writers.
   Verify data and schema against the backup, not against a changing live source.
4. Rehearse using the new artifact's exact migration set and engine selection.
   Before repair, require the only history problem to be failed V12, earlier applied
   checksums unchanged and all existing V12 objects valid. Unexpected definitions or
   nonempty untracked V13–V15 tables require a separate reconciliation plan.
5. Run explicit Flyway repair only after approval. Verify it removed only failed V12,
   with no checksum alignments or missing migrations marked deleted. Never set
   `success=1` manually, disable validation, skip SQL, run `clean`, or drop the source DB.
6. Migrate with `target=12`, inspect columns/index and validate. Then migrate through
   V15 and validate again. Repeating migrate must execute zero migrations. Confirm
   previous data unchanged; nullable barcode fields are newly NULL, not a backfill.
7. Before local application start, disable background Folio snapshot/recovery and
   accounting-price apply. A local Woo DB does **not** make the MSSQL endpoint local.
   Do not start a remote Folio write/sync as a side effect of health verification.

Normal health does not authorize a product sync. Sync verification is a separate
task using local Woo credentials and explicitly approved source access.

## Test gates

`WpFlywayMigrationConfigurationTest` verifies engine routing, unchanged legacy
MariaDB checksum and absence of duplicate packaged SQL V12. `WpFlywayV12DatabaseTest`
is opt-in and accepts only disposable localhost ports 13369/13370. It does not load
Folio/application beans. It covers the full migration chain, Spring Boot configuration,
failed MySQL SQL V12 recovery without rewriting earlier history, partial valid columns,
invalid columns/indexes, and existing MariaDB history validation **without repair**.

Test commands must use a fresh disposable DB server and private test password via
`WP_V12_TEST_PASSWORD`; never point these tests at LocalWP or production.

## Production startup regression, 2026-09-20

The supplied deployment log shows a successful image build, then Flyway validation
failure (`applied migration not resolved locally: 12` and `resolved migration not
applied: 12`). Automatic image rollback succeeded and the previous version returned
health 200. Production enables `useMysqlMetadata=true`, so the original customizer
mistook MariaDB for MySQL and offered JDBC V12 instead of the existing SQL V12.

The correction changes only vendor detection, not either versioned migration or its
checksum. Keep the current environment option; do not run production repair, edit
history, enable outOfOrder, disable validation or change database settings to bypass
the error. Rebuild the Java image from the corrected code. Before repeating deploy,
confirm the rollback container remains healthy and no long-running sync/recalculation
is active. Run the normal operator-controlled deploy, then verify internal/external
health and Flyway startup validation. A V15 database should require zero migrations.
If validation still fails, retain rollback and inspect the complete new error rather
than retrying or repairing automatically.

Regression coverage explicitly connects to disposable MariaDB 10.6.19 using
`useMysqlMetadata=true`, confirms JDBC reports MySQL while server detection reports
MariaDB, and checks validation, zero pending migrations, one SQL V12 with the original
checksum, successful Spring/Flyway startup and unchanged full migration history.
On 2026-09-20 this regression passed on the isolated MariaDB 10.6.19 instance:
3 database cases and 41 unit/mock cases passed; 7 MySQL-only cases were skipped.
The isolated test container was stopped afterwards. Production was not modified
or redeployed as part of this fix.

## Local incident, 2026-09-19

The original failed local V12 was reproduced on an isolated restored MySQL 8.0.35:
`ERROR 1064 (42000)` at `IF NOT EXISTS primary_barcode`. No V12 objects or V13–V15
tables existed in the source. All 15 SQL files in the old deployed JAR matched the
old source; this was SQL dialect incompatibility, not a missing deploy.

Private backups and detailed recovery evidence stay outside Git. Do not attach
database dumps, connection strings, passwords, raw environment or customer data
to this document or to a commit. A tested original backup is not a substitute for
a fresh backup after stopping writers immediately before an approved repair.

### Completed local verification

On 2026-09-19 the approved local recovery completed with PHP writers paused and
resumed in a `finally` guard. The fresh backup restored successfully on the isolated
MySQL clone; all 163 table checksums matched before source changes. Repair removed
only failed V12 and did not align/delete any other migration history. V12 and then
V13–V15 applied; validate passed and repeat migrate executed zero migrations.
The existing 73,464 metric rows were verified by a digest of every pre-existing
field (excluding the newly added NULL barcode). The other 160 existing tables had
unchanged data checksums; the staging table remained empty. Five new tables were empty.

Verification also passed on isolated MariaDB 10.6.19 with an already-applied SQL
V12 history: runtime validate and migrate required **no repair**. Tests executed:
24 unit/mock cases, 8 MySQL integration cases and 2 MariaDB integration cases.
Engine-specific cases were skipped on the opposite engine, not reported as executed.

Integration 2026-10-31 retains the deployed V12 implementation byte-for-byte and
extends the **MariaDB** opt-in regression with V18 → V20, unchanged earlier history,
the V19/V20 checksums, and a second migrate that executes zero changes. The original
MySQL V12 recovery/Boot fixture is explicitly capped at V15; it does not certify all
later migrations on MySQL. See [profit scope release recovery](FOLIO_PROFIT_SCOPE_SETTINGS_API.md)
for the current production incident and preflight. Production migrations are not
run by the packaging check or by these default-disabled database tests.

Local image `kreul-api:local-v12-mysql-20260919` was built from the tested JAR in an
isolated build context without `.env` or backup data. The old stopped container and
image were retained. This local-only image was not pushed or deployed to production.
`GET http://127.0.0.1:8080/healthz` returned `{"status":"UP"}` and startup validated
the local schema at V15 without further migrations.

**Temporary runtime isolation:** the current local container deliberately points
MSSQL at unavailable loopback with dummy credentials. Folio apply, product snapshot,
profit report, balance scheduled refresh and balance recovery are disabled. This
proves Java/Flyway startup, **not** connectivity or successful product synchronization.
For a separately authorized read-only Folio test, restore only the three original
`MSSQL_URL`, `MSSQL_USER`, `MSSQL_PASS` entries from the private original environment;
retain every safety override and the new image. Do not restore the entire old env
or restart the old image against the new MySQL JDBC-V12 history. Product sync was
not run as part of recovery. The private recovery handoff records exact backup,
environment and image paths/digests; none of those private files belongs in Git.

### Subsequent bounded-test runtime, 2026-09-20

After separate authorization for source reads and local Woo updates of KR-79406
and KR-49602, only the three original MSSQL connection variables were restored.
All existing OFF flags were retained; native-full and its diagnostics were also
disabled. The source account's permissions have **not** been proven read-only.
Code review confirmed the selected export DAO path uses SELECT; startup/pool health
uses SELECT 1. This is an operationally bounded read path, not a database permission grant.

The test Java container uses the same new image, binds only its internal loopback
and publishes no host ports. A temporary host-loopback proxy on port 18080 allows
only `/healthz` and exact `/sync/run` payloads with limit=1, pageSizeWoo=1, a boolean
dryRun and the two approved predecessor cursors. Other routes/payloads are blocked.
The normal port 8080 is intentionally unavailable to unrelated local clients.
Apply requires a clean one-product preview and is attempted at most once per cursor;
an unknown result must not be retried automatically. The frontend additionally guards
local Woo writes to unit-only updates of the same two SKU.

Proxy health returned UP and rejection checks returned 403 without forwarding.
However, the source connection timed out both from Java (HYT01 / socket connect
timeout) and directly from the Mac. No valid sync request was sent. This state is
**runtime prepared, source connectivity blocked**, not a successful synchronization.
Reconfirm connectivity before the separately owned frontend probe. No production
runtime/server/network configuration or Folio data was modified.

After confirming that blocker, the temporary proxy and its bounded test container
were stopped; only that newly created test container was removed. The preserved
new-image MSSQL-off recovery container was returned to the name `kreul-api` with
the original eight safety OFF flags and dummy loopback MSSQL credentials. Local
health is again on `127.0.0.1:8080`; no source reconnection/retry or sync is pending.
Backups and probe evidence/guard state were preserved. Source connectivity must be
resolved separately before another explicitly authorized test.
