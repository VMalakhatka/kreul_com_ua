# Durable Full-Card Synchronization

Contract v1, implementation verified locally 2026-10-23. Production deployment is
a separate operation. This replaces the WordPress full-catalogue HTTP wait; stock,
prices, supplier visibility settings and the full-card v2 hash are unchanged.

## API and Storage

All routes require `X-Auth-Token` matching `lavka.full-sync-token` from runtime
`LAVKA_FULL_SYNC_TOKEN`. If this dedicated value is unset/blank, existing deployments
fall back to `lavka.token` (`LAVKA_TOKEN`). When the dedicated value is set, only
that value authenticates these routes: it does not alter credentials of prices,
assembly, other Java controllers or outbound Java-to-Woo calls. Keep WordPress
Total Sync's existing `api_token`; configure Java to match it instead of rotating
the shared token. Both values unset disables this API (503 `JOB_API_NOT_CONFIGURED`);
missing/wrong credentials return 401 `UNAUTHORIZED`, including with the global
exception advice enabled. Responses are `Cache-Control: no-store`. Never log tokens.

- `GET /admin/sync/jobs/capabilities`: `protocol:1`, `persistent:true`,
  `maxChunkSize:100`, `activeJobId` (empty when none).
- `POST /admin/sync/jobs`: 202 with job state. Required request: canonical UUID
  `jobId`, UUID `lockToken`, `limit` 1..1000000, `pageSizeWoo` 1..100,
  `cursorAfter` (WordPress sends `!` initially), `dryRun`, `forceRefresh`.
- `GET /admin/sync/jobs/{id}`: state, or versioned 404 `JOB_NOT_FOUND`.
  Reading also wakes an idle job at a confirmed boundary, but never repeats an
  uncheckpointed chunk. Duplicate POST with exactly the same ID and payload is
  idempotent, including after completion. A changed payload is 400; a different
  active job is 409. Acceptance only persists intent; callback verification is
  performed by the background worker, never inside the POST response wait.
- `GET /admin/sync/jobs/{id}/chunks?after=0`: at most 100 durable chunk results,
  ordered by chunk number; paginate using the last returned number.

Flyway **V19** creates `lavka_full_sync_job` and `lavka_full_sync_chunk` in the
configured WordPress MariaDB datasource, not MSSQL. A unique nullable active slot
permits only one unresolved job. Results/history are retained; no automatic purge.
State JSON contains the private lease token; exports/backups need normal secret
handling. Public API excludes token, internal request and worker identity.

## Execution and Meaning of Counts

One Java worker reads bounded Woo windows (up to 100) and Folio diff additions
(up to the same size); writes are subdivided into batches of at most 25 items.
The existing diff can revisit a Woo comparison window while draining additions;
`processed` counts comparisons plus additions, not a unique-catalogue total or a
percentage. The configured limit is a safety budget checked after a chunk and can
be exceeded by the final bounded diff window. `PAUSED_LIMIT` is not catalogue end.
The Folio tail is processed even when no Woo products remain. Normal runs retain
hash-based skipping; force refresh ignores hashes. Dry run does not write products
or category descriptions; existing diff category-path resolution may create missing
categories, as in the legacy preview. Do not describe this as an entirely read-only
preview. The historical JSON counter `drafted` counts acknowledged `delete[]`
operations, not soft drafts: the existing full-sync deletion policy is unchanged.
The new UI labels it "Removed from Woo". Counts are checkpointed chunks only; an uncertain final chunk may have
additional writes that are deliberately not claimed as confirmed progress.

Before each chunk Java confirms the same WordPress lease through authenticated
`POST /wp-json/wc/v3/lavka-full-sync/lease` (read-only callback; Woo REST key user
must have `manage_woocommerce`). A missing callback pauses at the saved boundary;
a subsequent poll resumes the same ID. A connection-scoped MariaDB named lock
also excludes workers in another JVM and the legacy synchronous full-sync routes.

Before work, `inFlight=true` is persisted. After all acknowledged writes, cursor,
counts and the complete chunk result are committed together. If this checkpoint
fails, no next chunk runs. Recovery first obtains the MariaDB worker lock; an
uncheckpointed chunk becomes `OUTCOME_UNKNOWN`, never an automatic replay.

| State | Meaning | Safe to release WordPress lock |
|---|---|---|
| QUEUED / RUNNING | Active or waiting at a confirmed chunk boundary | No |
| COMPLETED | Catalogue exhausted, no recorded errors | Yes |
| COMPLETED_WITH_ERRORS | Explicit item rejections, or a confirmed read/preparation failure | Yes |
| PAUSED_LIMIT | Safety budget reached; cursor saved, catalogue incomplete | Yes |
| OUTCOME_UNKNOWN | Missing checkpoint or uncertain Woo/category write response | No |

Normal product errors in a complete Woo batch response are recorded and other
chunks continue. Malformed/truncated receipts and write transport failures never
trigger batch retries or recursive splitting. Category POSTs inside diff and
description writes also bypass legacy automatic retries in this workflow.
`safeToRelease:true` is emitted only after Java has stopped issuing writes for
this job; WordPress validates job identity and request fields before unlocking.

## Rollout and Recovery

1. Obtain deployment approval and ensure old synchronous Java full sync has
   actually finished. Its old WordPress timeout is not proof of completion.
2. Back up database/code. Deploy WordPress Total Sync 0.2.0, its JS/CSS/translations
   and `lavka-ecosystem-lock.php` together first. Old Java then leaves jobs waiting
   for a compatible API without taking a protected lease.
3. Deploy Java with V19 and verify health, authenticated capabilities and callback
   credentials. Do not repair/rewrite old Flyway checksums as part of this task.
   If Total Sync uses a different token from the other Woo services, set only
   `LAVKA_FULL_SYNC_TOKEN` in the private production env and its local deploy source,
   then recreate the container. Preserve `LAVKA_TOKEN`, other env keys and all Woo
   options. Compare credentials without printing them. On failure restore the
   previous image/env; a restart alone does not reload Docker environment values.
4. Start an approved bounded job. Check matching ID, chunk history, cursor,
   terminal status and released lock. A real full run requires separate approval.
5. Keep recurring WordPress cron and recovery ticks enabled. A terminal failure
   does not remove the next daily/weekly schedule.

On HTTP loss: query the same ID. A never-acknowledged ID may be submitted again
only after protocol-v1 `JOB_NOT_FOUND`, with identical ID/payload. An acknowledged
job disappearing means inconsistent/restored state, not permission to restart.
`OUTCOME_UNKNOWN` has no automatic reset or replay endpoint: inspect Java/Woo logs,
last chunk and actual affected cards, confirm all involved writers have ended,
then perform an explicitly approved reconciliation/recovery. Never clear the lock
solely because its timestamp is old. This conservative hold is intentional.

Rollback is safe only after a confirmed terminal job and lease release. Keep V19
tables/history; do not drop them, replay an uncertain chunk, or roll back the MU
lease guard while a job is unresolved. Legacy endpoints remain compatibility-only,
not the recommended large-catalogue transport.

## Verification

`FullSyncJobServiceTest`, `SyncDurableChunkTest`, `FullSyncJobControllerTest`,
`WooUnitAttributeClientTest` and existing full-card/unit/hash tests cover
idempotency, safe recovery, auth, malformed receipts and non-retrying writes.
`FullSyncJobMariaDbTest` is opt-in with `-Dfullsync.test.mariadb.port=<local-port>`;
it uses only disposable `full_sync_job_test`, checks V19, transaction rollback,
one active slot and cross-connection worker exclusion. No live Folio/Woo writes
are needed for this test suite.
