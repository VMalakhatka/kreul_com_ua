# Product cards: Access-compatible read/export

Verified 2026-10-18 against the supplied Access MSysQueries and the archived full
`dbo.card_tov` definition (CP1251). Owner: FolioProductCardDao/Controller.

POST `/admin/folio/product-cards/read`, header `X-Auth-Token` matching existing
`lavka.token` (LAVKA_TOKEN). Missing configuration fails 503; wrong token 401.
Body: `{"mode":"compare","skus":["00123"]}`. Modes: `direct` (1212 все поля),
`view` (_dbo_Все поля по Artic), `compare` (both). Empty skus selects all eligible
cards; otherwise at most 500 distinct exact article codes of 1–100 characters.
No SQL or identifiers are accepted from the client. Only current Paint_Ua is
supported; DB_NAME is checked before reading.

Response: `ok`, `sourceDatabase`, `mode`, `data:{columns:[{key,type}],rows:[[value]]}`,
`identical` (null except comparison), `directRows`, `viewRows`, `missingSkus`.
Values are decimal strings or original text, SQL NULL remains JSON null. No
floating point conversion. Columns are 54 positions in the supplied template,
using card_tov keys plus Now_COD_ARTIC at position 1. Both first columns use the
existing SKU: renaming or input mapping is outside this read feature. Selection
lists replace the two different Access local staging tables. Duplicate input SKUs
are removed, duplicate result rows are preserved. Comparison is an order-independent
multiset check across every typed field, including duplicate multiplicity and NULL
versus empty text; decimal scale differences are normalized.

Legacy warehouse IDs 7/12/5/15/1/3 and price names НАЦЕНКА/НАЦЕН_ОПТ are part of the
supplied query contract, not WordPress IDs. Direct mode preserves LEFT JOINs with
legacy WHERE filters: required warehouse/price rows must exist; ALL_ARTC and
ALL_CENA remain optional. `NACENKA_OPT_UA` comes from RUB_PRICE, not COEF_PRICE.
The view query reads dbo.card_tov, not card_tov_export. Neither route adds assembly
fields: template positions 55–57 are absent from both supplied queries.

All calls use SELECT only in a read-only SERIALIZABLE MSSQL transaction (60s),
query deadline 45s. Shared read locks can temporarily block concurrent edits;
read-only does not mean zero database load. TOP 50001 detects the 50000-row limit;
exceeding it fails rather than returning an apparently complete truncated file.
Failures return a safe 503 CARD_READ_FAILED, never raw SQL/JDBC errors. No schema,
Folio data, prices, counters, cron or snapshots are changed. No retries on server.

WordPress consumer: lavka-price-sync, separate Lavka Folio menu. Access MDB/macros
and the original workbook are neither uploaded nor executed. No new secrets or
migrations. Deploy Java before consumer use; existing LAVKA_TOKEN must match
WordPress price-sync API token. Rollback: revert these classes and consumer; no
DB rollback. Live equivalence still requires a read on the target environment;
unit tests alone do not prove the current view definition matches the archive.

Validation 2026-10-18: 5 isolated DAO/controller tests and Java 17 package build
passed. Live comparison not completed: the pre-existing local kreul-api container
was restarting; a separate JDBC attempt failed. No runtime restart, migration or
production deployment was performed. Current live equality remains unverified.
