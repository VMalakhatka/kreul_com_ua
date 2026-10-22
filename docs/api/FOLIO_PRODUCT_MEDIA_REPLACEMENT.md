# Previous main image replacement

Implemented in `FolioProductMediaService` and `FolioProductMediaDao`, 2026-10-22.
This extends the existing authenticated `POST /admin/folio/product-media/changes`.

When a manager replaces a product's main photo, WordPress requires an explicit
choice to keep the old photo at the end of the gallery or remove its display from
that product. Keep uses `add_gallery`/`update_gallery` with normal S3 proof;
remove uses `remove_gallery` for any existing gallery references to the old photo.
The new `set_main` and all gallery changes share one SKU transaction.

Example removal item inside `changes`:

```json
{
  "operation": "remove_gallery",
  "sku": "EXAMPLE-001",
  "recordId": "123",
  "expectedOldFilename": "example-old.jpg",
  "expectedOldSortOrder": 2
}
```

Preview uses `previewOnly=true`; apply uses `previewOnly=false` and the same
`externalRequestId`. The server resolves one product and a non-shared PLUS_ARTIC,
requires the gallery row to belong to it, compares both old values, and deletes
exactly one row with all of those predicates. Missing/wrong-product rows are blocked;
old-value changes block the entire SKU changeset. An affected-row count other than
one throws and rolls back. Replaying the identical successful request returns the
stored response without another deletion. Changing a payload under a used request
ID retains the existing idempotency conflict behavior.

Removal has `after: null`; there is no target filename or S3 proof. An object may
already be missing, so removing its reference does not require S3 access. No file,
WordPress attachment, other product or warehouse document is deleted. Existing
set/update/add validation remains unchanged. No schema migration is required.

Deploy this Java update before the corresponding `lavka-product-media-upload` 0.5.0
release. Live deployment and media writes require separate authorization. The
canonical manager instructions are `docs/MEDIA_MANAGER_GUIDE_UK.md` in paint-shop;
the shared endpoint contract is `docs/api/FOLIO_PRODUCT_MEDIA_API.md` there.

Local validation: `mvn -Dtest=FolioProductMediaServiceTest test` (Java 17, mocked DAOs;
no live Folio connection). Tests cover preview, exact preconditions, cross-product
and shared-group protection, same-SKU blocking, apply and idempotent replay.
