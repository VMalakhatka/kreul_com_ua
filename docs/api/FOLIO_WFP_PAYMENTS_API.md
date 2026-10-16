# WayForPay → independent Folio bank income

Owner: Java `folio.payment`; caller: WordPress `pc-wayforpay-checkbox` 0.2.0.
Reviewed 2026-10-16. Implemented, **not deployed or enabled in production**.

## Business meaning

Owner's revised rule: a verified payment is an independent accounting document.
Record the full gross UAH payment on warehouse **5**, even with no invoice, several
invoices, partial coverage, changed or archived invoices. Do not inspect or mutate
invoice/stock tables, wait for invoice creation, or allocate the same payment twice.
`CODCEL_POR=МАЛАФОП`, `VID_DOC=РЕАЛИЗАЦИЯ`, `TYPE_POR=NOT_NAL=STND_UCHET=UCHET_MOVE=VALUTROUBL=1`.
Initial `NOTOVAROST=SUM_POR`, `NACLAD_POR=0`, no `SCL_PMOV` rows. Distribution is a
separate Folio workflow; this API does not mark invoices paid.

WordPress durably captures the customer's Folio short key at the first verified
Approved notification (including the configured Internet client). Future customer
reassignment does not move this receipt. Java resolves its name from `_PARTNER`.
All payment secrets stay server-side. A signed WFP refund does not erase an earlier
Approved fact; WordPress flags a separate expense for review. No automatic expense,
netting of commission, new invoice, Checkbox receipt, card charge or historical backfill.

## Settings (all `folio.wfp-payment.*`)

| Property | Default / requirement |
|---|---|
| `api-enabled` | false; enables authenticated preview/status |
| `apply-enabled` | false; separate production write permission |
| `token` | empty; dedicated random secret, at least 32 characters; `X-Auth-Token` |
| `expected-database` | empty; exact approved database, checked again inside transaction |
| `source-scope` | empty; stable site identity `[a-zA-Z0-9._-]{1,64}` |
| `merchant-hash` | empty; SHA-256 of the configured WFP merchant account, lowercase hex |
| `start-at` | empty; fixed ISO UTC cutoff, identical to WordPress activation cutoff |
| `bank-template-id` | 0; explicitly reviewed existing bank-income technical ID |

Spring relaxed binding supports environment variables such as
`FOLIO_WFP_PAYMENT_API_ENABLED`, `FOLIO_WFP_PAYMENT_APPLY_ENABLED`,
`FOLIO_WFP_PAYMENT_TOKEN`, `FOLIO_WFP_PAYMENT_EXPECTED_DATABASE`,
`FOLIO_WFP_PAYMENT_SOURCE_SCOPE`, `FOLIO_WFP_PAYMENT_MERCHANT_HASH`,
`FOLIO_WFP_PAYMENT_START_AT`, `FOLIO_WFP_PAYMENT_BANK_TEMPLATE_ID`.
Restart Java after config changes. Do not paste secrets into Git, chat, logs or client JS.

The template must be a reviewed accounted bank income on warehouse 5 with the
above kind/type, domestic currency mode and zero tax amounts. Copy only its
`IS_NALPROD`, `NDS_TORGN`, `MY_ORG`; do not infer a tax rate from these legacy flags
or hardcode a historical template ID. Its flags/name participate in preview revision.

## HTTP contract

Base `/admin/folio/wfp-payments`. Dedicated token, `Cache-Control: no-store` on all
controller responses. Expose only through protected server-to-server networking.
The API trusts this authorized producer to verify WFP; it is not a browser API.

`POST /preview` (read-only) and `POST /apply` accept:

```json
{
  "operationKey": "<sha256(scope|merchantHash|reference)>",
  "sourceScope": "<stable-site-scope>",
  "merchantHash": "<64-lowercase-hex>",
  "reference": "42_woo_w4p_1792000000",
  "orderId": 42,
  "amountCents": 400,
  "currency": "UAH",
  "confirmedAt": "2026-10-16T12:00:00Z",
  "payerKey": "<Folio-short-key>",
  "expectedRevision": "<preview-revision; required only for apply>"
}
```

`confirmedAt` is the first durable server observation of valid Approved, UTC to
seconds, not unsigned provider processingDate or Woo date_paid. The accounting
date is that instant in **Europe/Kyiv**, not the time of a retry. Positive amount
up to 999,999,999 kopecks; order ID up to 12 digits; payer key 1–8 letters/digits,
spaces, dots, underscores or hyphens with no leading/trailing whitespace.
Unsupported/invalid data is visible review, never an invented payer/amount.

Canonical request SHA-256 over UTF-8:
`v1|sourceScope|merchantHash|reference|orderId|amountCents|currency|confirmedAt|payerKey`.
`expectedRevision` is not part of payment identity. Invoice IDs are not accepted
as payment identity or allocation commands.

Preview returns `status=READY`, `operationKey`, `requestHash`, `revision`,
`amountCents`, `paymentWarehouse=5`. An existing matching operation returns its
original result instead. Apply/status success returns `status=SUCCEEDED`, the
same identity/amount/warehouse and positive `paymentId`/`paymentNumber`.

`GET /{operationKey}` reads the durable result and verifies that the native income
still exists with the same amount, payer, kind and warehouse. Later distribution
does not invalidate it; missing/changed income returns `status=REVIEW`, never
recreates it. Status lookup works with `apply-enabled=false` while API stays enabled.

401 unauthorized; 422 invalid command/preview absent; 409 identity/revision,
template/payer/procedure/guard failure; 404 **only status not found**; 503 disabled,
misconfigured, database failure or uncertain outcome. No raw SQL/PII is returned.
Any attempted apply with lost/error response must be reconciled by GET. 404 after
uncertain apply is review, not permission for another POST or a new key.

## Atomicity, schema and recovery

Manual reviewed DDL: [folio-wfp-payments-manual.sql](../sql/folio-wfp-payments-manual.sql).
There is **no HTTP/startup migration** for MSSQL. The checked-in exact database guard
is Paint_Rus; production DBA changes it only for a separately approved deployment.
Retain the ledger permanently, including after Folio archive/delete. Unique key
and unique scope/merchant/reference protect identity; native ID is also unique.

Apply uses a dedicated serializable `mssqlTransactionManager` transaction:
locked ledger lookup → transaction depth=1 → exact four procedure fingerprints →
locked payer/template re-read and revision check → native `INSERT_PLAT` with an
empty, collision-checked negative TMP key → header/no-allocation/TMP postconditions
→ ledger INSERT → same outer commit. No MariaDB or HTTP inside this transaction.
The real ledger table access starts jTDS's implicit transaction before the native
RPC; do not move BEGIN TRAN into that RPC. Any error rolls back payment and ledger.

Do not reset sent markers, delete the ledger, re-run Approved, create an invoice
or use a new key to recover a timeout. First inspect status and the native WFP
reference. An unknown legacy/manual receipt without a ledger is `existing_payment_requires_review`.

## Verification and release gates

Offline: `mvn -o -Dtest=WfpPaymentTest -Djacoco.skip=true test` (Java auth,
cross-language hash, configuration gates, revision/identity conflicts, atomic
service ordering, rollback on ledger failure, duplicate result, no-invoice protocol).
WordPress: plugin `tests/folio-payments.php` (live/preview/off), existing callback and
return-page tests; real local two-connection advisory locks and schema migration.

Native probe: [unallocated rollback](../folio-experiments/2026-10-16-unallocated-payment-rollback.sql),
[independent baseline](../folio-experiments/2026-10-16-unallocated-payment-baseline.sql).
Paint_Rus SQL Server 8.00.760, compatibility80/CP1251, explicit owner backup/idle approval.
Initial NULL document-number probe failed (SQL515, boundary unknown); independent
read found no fixture remains. Schema/default and existing unallocated income
examples established **NACLAD_POR=0**, not NULL. Corrected run
`7ee2952c-7663-4d3a-8f28-df202eeaffd4`: native return0, amount4/unallocated4,
warehouse5, allocations0, transaction1→1, outer rollback confirmed.
Independent connection `1e5ca5bc-2b25-4077-84ab-3269e5e72f25`: all four fixture
counts0 and no remaining transaction. This is not a production API acceptance test.

Still required before production enablement: approved ledger DDL/permissions,
deployed Java + WP versions, private token/network checks, reviewed bank template,
fresh fixed start boundary, Java read-only preview, one approved new live payment,
Folio desktop/debt report and independent ledger verification. Full HTTP/native
runtime concurrency and commit-loss testing are not claimed by offline mocks.
Rollback: pause WP, disable Java apply; preserve both journals for GET reconciliation.
