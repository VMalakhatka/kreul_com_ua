# Full Sync: Supplier and Complete Card Hash

Verified: 2026-10-22, isolated Java 17 unit tests and local Woo REST fixtures.
No production deployment or full live synchronization was performed.

## Source and Contract

`CardTovExportDaoImpl` preserves the legacy `card_tov_export` queries. It enriches
each result in batches of at most 1000 exact SKU parameters from
`SCL_ARTC.DOP2_ARTIC`, `ID_SCLAD=7`, the full-card source warehouse. No view, table,
Folio data or stock/price logic changes. A missing/ambiguous source row fails the
read; it must never be confused with a confirmed empty supplier. The latter is
exported as `supplier: ""`. The source SKU is not rewritten.

`CardTovExportOutDto.supplier` is included in page, diff/create and diff/update.
`SyncServiceImpl` sends it as `lavka_supplier` in the existing Woo v3 product
batch. This is a field implemented by `lavka-total-sync` 0.1.2, not a new endpoint.
WordPress owns resolving/creating `product_brand` terms and setting `brand_ids`
through product CRUD. An explicit empty string clears the assignment. An absent
field in a legacy request leaves it unchanged. No Woo supplier visibility/order
option is written. Newly created supplier terms require explicit display approval.

Before the first diff, Java checks OPTIONS `/products` for a string
`lavka_supplier` property with `x-lavka-full-sync-supplier: true`. Missing support
stops with `WOO_FULL_SYNC_SUPPLIER_UNSUPPORTED`, before diff/category/product writes.
Deploy PHP first. Do not bypass the capability check or send the value only as
unconsumed product metadata. Dry run performs no supplier term/product writes;
the pre-existing category-mapping behavior of export/diff is unchanged.

## Hash

`FullSyncHash` replaces the delimiter-joined payload with deterministic,
alphabetically ordered JSON of the complete exported DTO, excluding only `hash`,
and adds `_hashSchema: full-card-v2` before SHA-256. The stored `_ms_hash` remains
64 hexadecimal characters. All 16 current source/derived fields are covered:
SKU, name, image reference, unit, barcode, weight, length, width, height, source
status, packaging weight, description, package/size text, category description,
resolved category ID and supplier. Future serialized DTO fields enter the hash
automatically. JSON escaping preserves boundaries; null, empty, zero, whitespace
and delimiter-containing values cannot collapse into the same input text.

The first complete post-deploy pass changes old hashes and updates existing
cards, including missing supplier assignments. Run from the beginning, not a
saved tail cursor. Subsequent unchanged cards are skipped; a supplier change or
removal triggers an ordinary update. Prices/stocks, the separate gallery/media
workflow, display settings and Woo manual fields are not source-card fields.
Hash coverage does not change existing optional-field clearing policies for other
fields, nor does it detect manual Woo edits when Folio and its stored hash are
unchanged. Use the existing explicit force refresh for that case.

## Errors and Verification

PHP resolves the supplier before product/hash save and verifies the persisted
relationship after save. A failed readback removes `_ms_hash` and returns an item
error, so the next pass retries. Term-creation failure leaves the previous hash.
Woo HTTP 200 is not sufficient: Java counts successful items and includes safe
per-item error codes in the report without reposting confirmed successful items.
Unknown transport outcomes retain the pre-existing batch behavior; this change
does not redesign the global retry/lock or deletion policy.

Tests: `FullSyncHashTest` mutates every record field and checks separator/null
boundaries, supplier changes, unchanged diff and old-hash backfill;
`CardTovSupplierTest` covers all DAO read paths and missing/duplicate identities;
`SyncServiceUnitAttributeTest` covers both product payloads, guard, preview and
per-item errors; `WooUnitAttributeClientTest` covers schema and HTTP 200 failures.
All use mocks, not a live application context. WordPress integration and operator
release steps are in its `docs/api/FOLIO_FULL_SYNC_SUPPLIER_RELEASE.md`.

Rollback Java and PHP as a pair after stopping full sync. Restoring code does not
undo assigned relationships or remove created terms; restore exact saved data only
with separate approval. The old Java can continue with the new PHP (field absent).
No schema migration, index rebuild or forced product deletion is required.
