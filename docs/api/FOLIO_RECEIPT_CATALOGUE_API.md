# Folio receipt catalogue for manager email mailings

Verified: 2026-10-05 against current Java code and isolated controller/DAO tests.
Owner: `FolioReceiptCatalogueController`, `FolioReceiptCatalogueDao`.
Production deployment, live database result/latency validation and real email
acceptance are pending. This API does not send emails or modify Folio documents.
WordPress `pc-order-import-export` owns selection, prices, XLSX and email queue;
operator steps live in WordPress `docs/OPERATIONS_RUNBOOK.md` and
`docs/MANAGER_CUSTOMER_GUIDE_UK.md#mailings`.

## Access and scope

Base: `/admin/folio/receipt-catalogue`. These are internal admin GET endpoints,
consumed by the existing WordPress server-side Folio proxy. WordPress enforces
`manage_woocommerce` plus a nonce. The Java service relies on the existing
restricted admin network/reverse-proxy boundary; the path name itself is NOT
authentication. Do not expose `/admin/**` publicly. No credentials appear in
browser JavaScript. No schema migrations, stored procedures or business writes.

Only active `SCL_NAKL`/`SCL_MOVE` are read. Receipt type is Cyrillic `П` (U+041F),
header accounted flag `STND_UCHET=1`, `ISNULL(VOZVRAT_PR,0)=0`. No archive tables,
returns, non-accounting documents, purchase costs, amounts or supplier identities
are returned. SQL Server 2000 compatible `TOP`, bound parameters and keyset
pagination are used; no OFFSET/CTE/NOLOCK. This confirms implementation scope,
not a live data audit. Date is the document calendar day, not a UTC conversion.

## Warehouse picker

`GET /warehouses`

```json
{"ok":true,"warehouses":[{"code":"7","name":"Example warehouse","descr":null}]}
```

Uses existing `MsWarehouseDao.findAllVisible()`, `SCLAD_R.C_1='1'` warehouses.
The API does not invent sales or arrival warehouse IDs.

## Receipt picker

`GET ?warehouseId=7&date=2026-10-05&afterId=0`

Positive warehouse, date in 1900–2099, nonnegative cursor. Spring rejects malformed
parameters. Date condition is `[day 00:00, next day 00:00)`, including the entire
day. Header ID must be greater than afterId. Read 101, return up to 100 documents:

```json
{
  "ok":true,"warehouseId":7,"date":"2026-10-05",
  "documents":[{"id":9001,"number":"501a","date":"2026-10-05","warehouseId":7}],
  "hasMore":false,"nextAfterId":0
}
```

`id` is `UNICUM_NUM`, the unambiguous internal identifier; `number` is
`N_PLAT_POR` without decimal zeroes plus trimmed `DOPN_SCHET`. On a full page with
another row, `nextAfterId` is the last returned ID; request that cursor, never
last ID+1. No financial total is needed for this picker. Nested LocalDate values
use the application's Jackson date serialization; the WP picker accepts ISO
strings and date arrays. Echoed top-level dates are always ISO strings.

## Mini price-list SKU selection

`GET /9001/skus?warehouseId=7&date=2026-10-05`

```json
{"ok":true,"documentId":9001,"warehouseId":7,"date":"2026-10-05","skus":["00123","АБВ"]}
```

Positive document ID required. One joined read revalidates exact header ID,
warehouse, date and accounted non-return receipt. Lines must also be accounted,
non-return, positive quantity and from the selected warehouse. Trimmed nonempty
SKUs are DISTINCT; they remain strings (leading zeroes preserved).
No eligible lines returns 404; more than 2000 distinct SKUs returns 400 without
silently truncating a mailing. Bounded query reads at most 2001. Other invalid
parameters return 400; database errors fail the request, never appear as a valid
empty price list.

WordPress checks the echoed selection and maps SKUs to published eligible site
products. Missing/hidden SKUs are explicitly shown in preview. The receipt gives
only the product scope. Site customer prices and current Kyiv/Odesa stock are
used, not receipt purchase prices or receipt quantities. Neither accounting
customer identity nor a document write is involved.

## Release, evidence and rollback

Deploy Java and WordPress updates for arrival selection. Text/full-price-list
mailings can work without this endpoint; missing Java support causes a visible
picker error and no fallback to a full catalogue. Local tests use mocked JDBC,
HTTP, emails and cron, not live Folio. Java unit command:

```sh
mvn -q -Dtest=FolioReceiptCatalogueTest -Djacoco.skip=true test
```

Tests cover pagination, selection validation, limits, SKU-only response and
SQL constraints including separate header/line warehouse checks. After deployment,
compare a known receipt's date, warehouse, number and SKU list read-only; no real
customer email is needed to check the picker. Existing receipt changes between
picker and preview are revalidated by the joined SKU query. WordPress preview is
an immutable mailing snapshot afterwards. Rollback restores the earlier Java
artifact; no database rollback or migration is required. Existing prepared
mailing snapshots are WordPress data and do not need this endpoint to send.
