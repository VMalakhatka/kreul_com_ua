# Product packaging for commercial offers

Read-only `POST /admin/folio/product-packaging`, body `{"skus":["SKU"]}` (1–500 nonblank strings, max 100 characters). Uses the existing customer-import token via `X-Auth-Token`; no new secret. Missing configuration: 503, wrong token: 401, invalid input: 400. Response is a no-store JSON array of `{sku, unitsPerPack}`; missing SKUs are omitted, database NULL remains null. No prices or personal data.

Source: `SCL_ARTC.EDN_V_UPAK` at catalogue source warehouse 7, consistent with `card_tov_export` / `CardTovExportDaoImpl`. Existing `SclArtcMapper` and product snapshot DAO confirm the column in code. Do not derive pack quantity from EDIN_IZMER (unit/volume), RAZM_IZMER (dimensions) or stock. Parameterized SQL is SQL Server 2000 compatible and reads only the requested batch.

WordPress PCOE requests packaging while preparing commercial offers. Nonpositive/unknown packaging stays blank. API failure stops preparation instead of silently mailing an incomplete file. Standard price lists do not depend on this endpoint. Deploy Java before WordPress; no sync or database migration needed. Existing prepared files remain unchanged, so prepare a new mailing after deployment. Unit tests mock the DAO and require no live database.
