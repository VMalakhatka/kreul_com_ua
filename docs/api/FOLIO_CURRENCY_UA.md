# Currency price Ukraine

Verified: 2026-10-22, source review and mocked unit tests. Not deployed; no live writes performed.

Owner confirmation on 2026-10-22: retain the two Access query effects exactly, including the warehouse-7 filter, with both updates exposed as one manager operation. Source: Запросы-TS.mdb / Валютная Цена Украина; `Валютная_Цена_Плюс`, then `Валютка_Розница`. WordPress manager guide: `paint-shop/docs/api/FOLIO_CURRENCY_UA.md`.

## API

POST `/admin/folio/currency-ua/{preview|apply|status}`; mandatory `X-Auth-Token` matches Lavka API token in constant time. No-store responses, sanitized error codes. Folio connection must resolve to Paint_Ua.

Preview body:
```json
{"rows":[{"sku":"ML-47068","price":"1.8987","currency":"3"},{"sku":"ML-18270","price":"1.6974","currency":"3"}]}
```
Apply body: `{"token":"<preview UUID>","confirmed":true}`. Status body: `{"token":"<preview UUID>"}`. Apply ignores client row data and uses the stored immutable preview.

Result: token, status, sourceDatabase, input, before, after, missingCards, missingRetail, cardRows, retailRows, expiresAt, error. Each snapshot row: input (zero-based index), table, sku (actual matched database code), warehouse, id (SCL_PRIC.ID or null), price (decimal string/null), currency (card code/null). Full before/after rows are returned, max 5000. Counts mean matched rows, including rows whose assigned values are unchanged.

Inputs: 1–500 unique articles; case-insensitive duplicates rejected before database calls. SKU <=20, currency <=4 characters, both representable in CP1251 with no control characters. Price is a decimal string (comma accepted), <=64 characters, finite and non-underflowing as SQL float. No conversion, markup, currency existence policy or sign restriction is added. BigDecimal used for JDBC values; original columns remain float. Different input spellings resolving to the same database target under SQL collation are rejected.

## Database behavior

Cards: `UPDATE dbo.SCL_ARTC SET CENA_VALT=?,COD_VALT=? WHERE COD_ARTIC=?` — all warehouses.

Then prices: `UPDATE dbo.SCL_PRIC SET RUB_PRICE=? WHERE COD_ARTIC=? AND NAME_PRICE='ЯЯ_Р_ВАЛЮТ' AND ID_SCLAD=7`.

No inserts or extra field mutations. Missing cards/prices are reported and skipped, matching the original UPDATEs. Every existing matching SCL_PRIC row is updated, including multiple rows distinguished by ID. The entire file is one transaction, all card updates followed by all additional-price updates. SQL Server 2000-compatible queries and jTDS retained.

Preflight checks DB_NAME, documented syscolumns types/lengths and no table triggers. Baseline: 2026-08-11 metadata snapshot (card key COD_ARTIC+ID_SCLAD, price key ID, price columns float, currency varchar(4)). Runtime mismatch fails closed; no schema or permission changes. Preview uses SELECT without update locks. Apply uses mssqlTransactionManager, existing accounting-price application mutex, then UPDLOCK/HOLDLOCK snapshot reread. Exact snapshot equality protects against stale rows/additions/deletions; mismatches abort before updates. Every update row count must match preview, final snapshot is checked before commit. A failure rolls back the transaction; transport/commit exceptions are reported UNKNOWN because durable outcome may be uncertain.

## Operation lifetime and retries

PREVIEW expires after 15 minutes. Per-process cache retains jobs 24 hours, cap 100 (stale entries cleaned on preview creation). Cache does not survive restart and is not a durable database journal. Each UUID can leave PREVIEW only once; concurrent/replayed apply returns the current outcome without another write. No automatic retries. WP persists per-user audit, token, before/after data and durable send claim; reuse of a claimed operation never resends apply. A missing backend token after restart requires manual reconciliation for an uncertain operation, not a new blind write.

Statuses: PREVIEW, APPLYING, APPLIED, STALE, EXPIRED, EMPTY, UNKNOWN. APPLIED means transaction commit returned successfully and post-update snapshot was checked. UNKNOWN may include a rolled-back error or an uncertain commit; do not claim it is guaranteed failure. STALE has no updates. Empty target sets cannot be applied. Authorization failures 401; disabled token 503; invalid request/unknown token 400; preview/read/preflight error 503. Errors do not expose SQL, connection data or raw JDBC messages.

## Validation / release

`mvn -Dtest=FolioCurrencyPriceServiceTest,FolioCurrencyPriceDaoTest,FolioCurrencyPriceControllerTest -Djacoco.skip=true test` (JDK 17): 10 mocked tests, covering auth, both scopes, decimal values, transaction ownership, rollback on second failure/count mismatch, stale snapshots, replay and missing targets. These tests do not prove live write rights or real SQL Server locking behavior. Compile passed. No integration test against live Folio was run.

Deploy backend before WordPress. Existing connection must have targeted SELECT/UPDATE permissions and ability to use the existing application mutex. Perform authorized read-only preview/schema check before first real apply; manager then confirms that exact preview. No Folio migration. Code rollback does not revert committed prices; use stored before snapshots and a separately reviewed correction if needed.
