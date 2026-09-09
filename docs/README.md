# KREUL Java Documentation

This directory contains exact Java/Folio contracts and evidence. The canonical
cross-system guides for people are maintained in the WordPress repository:
`docs/BACKEND_GUIDE.md`, `docs/JAVA_DOCKER_RUNTIME.md` and
`docs/BOOTSTRAP_AND_RECOVERY.md`.

Verified against the current repository structure: 2026-09-06.

## Find the Right Document

| Need | Source |
|---|---|
| Understand or change an HTTP contract | The matching file in [`api/`](api/) and the current controller/DTO/service |
| Understand Folio account behavior | [`business/01_ACCOUNT.md`](business/01_ACCOUNT.md), [`api/FOLIO_ACCOUNT_JS_API.md`](api/FOLIO_ACCOUNT_JS_API.md), [`api/ACCOUNT_WRITE_MAPPING.md`](api/ACCOUNT_WRITE_MAPPING.md) |
| Inspect Folio tables and evidence | [`00_DATABASE_CATALOG.md`](00_DATABASE_CATALOG.md) and `.agents/skills/work-with-folio-mssql/references/` |
| Work with product snapshots and analytics | [`api/FOLIO_PRODUCT_SNAPSHOT_API.md`](api/FOLIO_PRODUCT_SNAPSHOT_API.md), [`api/FOLIO_PRODUCT_ANALYTICS_API.md`](api/FOLIO_PRODUCT_ANALYTICS_API.md), [`api/FOLIO_PRODUCT_ANALYTICS_FRONTEND_HANDOFF_V4.md`](api/FOLIO_PRODUCT_ANALYTICS_FRONTEND_HANDOFF_V4.md) and the current Flyway migrations |
| Physical availability, stockout days and configured warehouse groups | [`api/FOLIO_PRODUCT_AVAILABILITY_FRONTEND_V5.md`](api/FOLIO_PRODUCT_AVAILABILITY_FRONTEND_V5.md); snapshot owns V13 active/stage monthly masks, analytics owns query-time group evaluation |
| Configurable transit warehouses and purchase-planning safeguards | [`api/FOLIO_TRANSIT_WAREHOUSES_FRONTEND.md`](api/FOLIO_TRANSIT_WAREHOUSES_FRONTEND.md); calculation v3 separates network physical stock from supplier transit; independent snapshots block confirmed network totals until cross-warehouse consistency is proved |
| Work with accounting-price operations | [`api/FOLIO_ACCOUNTING_PRICES_API.md`](api/FOLIO_ACCOUNTING_PRICES_API.md) and [`api/FOLIO_ACCOUNTING_PRICE_OPERATIONS_FRONTEND.md`](api/FOLIO_ACCOUNTING_PRICE_OPERATIONS_FRONTEND.md); V14 owns durable arithmetic diagnostics and confirmed-rollback SKU skips |
| Work with balances, debtors or customer documents | Matching `FOLIO_CUSTOMER_*` documents in [`api/`](api/) |
| Audit cash/bank document quality independently of profit | [`api/FOLIO_DOCUMENT_AUDIT_API.md`](api/FOLIO_DOCUMENT_AUDIT_API.md); paginated SCL_PLAT, all warehouses/directions, category and ERROR/RULE_REVIEW separated, default-disabled until admin access is restricted |
| Work with the monthly Kyiv/Odesa profit report | [`api/FOLIO_PROFIT_REPORT_API.md`](api/FOLIO_PROFIT_REPORT_API.md) and [`api/FOLIO_PROFIT_REPORT_FRONTEND_TASK.md`](api/FOLIO_PROFIT_REPORT_FRONTEND_TASK.md); Java owns expenseLines, adjacent-month candidates, provisional period diagnostics, per-document tax allocation and independent section availability with bounded reads (2026-09-08.2) |
| Save and browse monthly profit report revisions | [`api/FOLIO_PROFIT_SAVED_REPORTS_API.md`](api/FOLIO_PROFIT_SAVED_REPORTS_API.md); V15 application-MariaDB history, explicit idempotent calculation, saved-only GET and ranges up to 24 months |
| Work with product media | The matching `FOLIO_*MEDIA*` document in [`api/`](api/) and the current controller/service/DAO |
| Run an isolated Paint_Rus experiment | [`folio-experiments/00_PAINT_RUS_EXPERIMENTS.md`](folio-experiments/00_PAINT_RUS_EXPERIMENTS.md) and the Folio skill safety rules |
| Validate SAFE negative-correction arithmetic | [`folio-experiments/36_negative_correction_validation.md`](folio-experiments/36_negative_correction_validation.md); candidate scripts 33/34, 15 SQL 2000 fixture cases, production installation gated |
| Check installed SAFE negative-correction wrapper | [`folio-experiments/37_installed_safe_negative_correction_golden_master.md`](folio-experiments/37_installed_safe_negative_correction_golden_master.md); 13 real-wrapper rollback cases in Paint_Rus, independent postchecks, no Paint_Ua installation |
| Build, run or deploy Java | WordPress `docs/JAVA_DOCKER_RUNTIME.md` and `$build-java-docker-runtime` |

## Source of Truth

Use this order when sources disagree:

1. Current live schema or a reproducible isolated experiment.
2. Current executable code and tests.
3. Exact API or business document marked as verified.
4. Database catalog and manufacturer documentation.
5. Roadmaps, old notes and hypotheses.

Do not average conflicting sources. Record the difference and follow the stronger
source for the affected installation.

## Backend Shape

```text
WordPress/PHP server-side proxy
        |
        v
Spring controllers -> services -> DAO
        |                         |
        |                         +-> Folio / legacy MS SQL via jTDS
        +----------------------------> WordPress MariaDB via JDBC/Flyway
```

Folio and MariaDB have separate data sources and transaction managers. A commit in
one database cannot atomically commit the other. Every cross-database command needs
an idempotency key, an observable terminal state and an explicit recovery path.

## API Families

- `/healthz` and `/internal/*` - runtime and narrow diagnostics.
- `/sync/*` and `/admin/sync/*` - product synchronization.
- `/admin/stock/*`, `/goods/*`, `/ref/*` - goods, stock, prices and directories.
- `/admin/folio/accounts*` and `/admin/folio/order-accounts` - Folio accounts and
  Woo order conversion.
- `/admin/folio/accounting-prices/*` - accounting-price jobs and product snapshots.
- `/admin/folio/customer-*`, `/admin/folio/partners` - customer data and documents.
- `/admin/folio/product-media`, `/admin/media/*` - Folio/Woo/S3 media workflows.
- `/admin/folio/profit-report` - monthly profit report; Odesa master-class income
  and returns are resolved from exact Folio invoice lines, while the additional
  Odesa salary defaults to `5000.00` and can be overridden with
  `LAVKA_FOLIO_PROFIT_REPORT_ODESA_ADDITIONAL_SALARY`.

The route name is not an authorization boundary. Before exposing or extending
mutating `/admin` or `/sync` routes, verify incoming authentication, authorization,
network restrictions, request IDs and safe errors.

## Data and Migrations

- Folio/MS SQL remains the authority for accounting documents, partners, stock and
  accounting prices.
- WordPress MariaDB stores site data, idempotency records and read projections.
- Flyway migrations are in `src/main/resources/db/wp/migration/` and apply to the
  WordPress-side MariaDB data source.
- The product snapshot key is `source_database + warehouse_id + sku`; joining only
  by SKU is incorrect.
- A snapshot/projection is not a replacement for the source Folio record.

## Change Documentation

Update documentation in the same change:

| Change | Required document |
|---|---|
| Controller route, DTO field or terminal status | Exact `docs/api/*.md` contract |
| Folio SQL, schema assumption or business write | Relevant `docs/business/*`, catalog card and Folio skill reference when the invariant is reusable |
| Flyway table or projection lifecycle | Owning API contract plus this map if ownership changes |
| Product metric or formula | Snapshot/profit contract with source, formula and limits |
| Dockerfile, Compose, env keys, port, health, deploy or rollback | WordPress `docs/JAVA_DOCKER_RUNTIME.md` |
| Cross-system flow, bootstrap or recovery | WordPress `docs/BACKEND_GUIDE.md` or `docs/BOOTSTRAP_AND_RECOVERY.md` |

Mark facts as `Confirmed`, `Calculation`, `Inference`, `Needs verification` or
`Plan`. Never add credential values, connection strings, internal addresses,
customer payloads or raw database definitions containing private literals.

## Автоматическая проверка

```bash
python3 scripts/check-documentation.py
python3 scripts/check-documentation.py --working-tree
```

Проверка требует обновить `docs/api` при изменении controller/DTO, профильный
API/business документ при изменении Folio service/DAO, документацию проекций при
изменении Flyway и Java README при изменении runtime/env/deploy. Она проверяет
относительные ссылки, но не доказывает смысловую правильность текста.
