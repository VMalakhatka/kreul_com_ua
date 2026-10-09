# Assembly recipe closure (2026-10-09)

Owner: `FolioAssemblyController`, `FolioAssemblyDao`, `FolioAssemblyGraph`.
Implemented and tested locally; production acceptance/deployment pending.

`POST /admin/folio/product-analytics/assembly-graph`

```json
{"sourceDatabase":"Paint_Ua","warehouseIds":[1,7],"rootSkus":["CL-96371CR"]}
```

Requires `X-Auth-Token` equal to existing `lavka.token`. No configured token ->503;
missing/wrong token ->401. Only Paint_Ua/Paint_Rus, nonempty positive warehouse
IDs (max100), nonblank SKUs (max100 characters, max10000 roots). Source DB_NAME
must match; no source database identifiers from the request enter SQL.

Response `version:2`, SHA-256 `revision`, `nodes` and `edges`:

- node: `sku`, `manufactured`, `issues`.
- edge: `parent`, `child`, `factor` (component units per child), `source`, `rowId`.
- simple `ALL_RAZBORKA`: `ART` parent, `ART_R` child, factor `1/KOL_R`.
- complex `ALL_RAZBORKA_SLOJ`: `ARTIC_ROD` parent, `ARTIC_REB` child, factor `KOL_R`.
- complex replaces the entire simple recipe of the same child, including when
  complex data is invalid; never fall back silently.

Closure walks parent -> consuming child, independently of suppliers. All reached
children's component edges are returned, including other suppliers' components.
Those extra components do NOT become nodes/roots and their unrelated consumers
are not loaded. Root and child identities use trim + uppercase.

`SCL_ARTC.BALL4` is read for closure nodes on selected warehouses. Flags3/9/11/12
or having any recipe mark a node manufactured. Missing/conflicting/unknown role,
missing child recipe, empty SKU, coefficient<=0, self-link, duplicate component
or multiple simple parents are review issues. Invalid edges remain visible with
null factor, to retain the diagnostic path; WordPress must not use them as zero.
WordPress topological evaluation detects multi-node cycles and blocks ancestors.

Read-only `mssqlTransactionManager` serializable transaction, 60-second timeout;
prepared statements with 30-second timeouts, SQL Server2000 compatible. No schema
migrations or source writes. Each recipe table is bounded at200000 rows, closure
at10000 nodes; limit violation fails instead of truncating. Cards are queried in
chunks of300 SKU. Revision includes effective edges and role-derived decisions.

WordPress previewVersion9 freezes the graph, loads missing children from existing
analytics query schema7 using identical warehouse/period/generation scope and
without product/availability filters. One forecast per SKU; stock netted once;
only manufacturing shortages propagate with fractional coefficients. The existing
purchase model retains group routes/transit/stockout checks and root rounding.
Supplier export allows only original purchasing roots of the requested supplier.
Child input edits recalculate the graph. Before export WordPress rereads revision
and rejects changed recipes. This endpoint creates no assembly/order documents.

Tests: `FolioAssemblyGraphTest`, `FolioAssemblyControllerTest`; frontend model,
session and UI tests live with `lavka-price-sync`. Release Java before WordPress;
ensure WordPress `api_token` matches `lavka.token`, start a new preview, reconcile
simple/complex recipes and a grandchild chain before rollout. No production data
was changed during implementation. Cross-system instructions/formulas are in
WordPress `docs/api/FOLIO_ASSEMBLY_PURCHASE.md` and `docs/OPERATIONS_RUNBOOK.md`.

Version2 (2026-10-09) also guarantees that analytics regularSoldUnits and
salesOnAvailableDays exclude historical МУЛЬТИСБОРКА consumption even in an
explicit INCLUDE selection. That quantity remains in expenseQuantity. Selected
РАСХОДНИКИ and regular sales retain their demand semantics. Child manufacturing
shortages now own component demand. Existing snapshot facts need no migration.
WordPress requires version2 before accepting the completed purchase preview;
old scenario operation selections remain valid without rewriting saved settings.
Local regression: FolioAvailabilityMariaDbTest checks mixed/assembly-only demand,
financial sales, actual expense, stock-only warehouses and available-day demand.
