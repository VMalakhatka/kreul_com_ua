# Task: Folio partners endpoint for WooCommerce customer mapping

Status: implemented in Java backend.

Implementation files:

- `src/main/java/org/example/proect/lavka/controller/FolioPartnerController.java`
- `src/main/java/org/example/proect/lavka/service/folio/FolioPartnerService.java`
- `src/main/java/org/example/proect/lavka/dao/folio/FolioPartnerDao.java`
- `src/main/java/org/example/proect/lavka/dto/folio/FolioPartnerItemResponse.java`
- `src/main/java/org/example/proect/lavka/dto/folio/FolioPartnersResponse.java`

## Goal

Add a read-only Java backend endpoint that returns Folio organizations/partners for selecting a Folio client in a WooCommerce user profile.

The endpoint is needed for the future Woo mapping:

```text
Woo user -> Folio client
```

Woo will use the selected Folio client when building a preview JSON for Folio account creation.

## Proposed Endpoint

Preferred path:

```http
GET /admin/folio/partners
```

Implemented path:

```http
GET /admin/folio/partners
```

Alternative path if the implementation should be account-specific:

```http
GET /admin/folio/account-partners
```

## Data Sources

Primary table:

```text
_PARTNER
```

Optional additional tables if relationships are already clear:

```text
_PARTNER_PL
VID_DEAT
TIP_ORG
_PARTNER_TYPES
GOROD
```

For the first implementation, `_PARTNER` is enough. The response shape should allow adding extra requisites later without breaking Woo.

## Organization Type Filter

Use:

```text
_PARTNER.MY_ORGANIZ
```

Known values:

| Value | Meaning |
|---|---|
| `Я` | Own organization / my organizations |
| `П` | Partner |
| `Д` | Dealer |
| `К` | Buyer/customer |
| `Т` | Supplier |
| `I` | Foreign supplier |

Important: `Я`, `П`, `Д`, `К`, `Т` are Cyrillic characters. Do not replace them with visually similar Latin characters.

Default filter should return only values useful for account customer selection:

```text
П, Д, К
```

Support query parameter:

```http
GET /admin/folio/partners?types=П,Д,К
GET /admin/folio/partners?types=all
```

## Search

There can be many organizations, so the endpoint must support search:

```http
GET /admin/folio/partners?q=баев
```

Search at least by:

```text
NAME_USER
NAMEP_USER
N_USER
```

If the schema has other reliable short-name/code fields, they can also be included.

## Pagination

Do not return the entire directory by default.

```http
GET /admin/folio/partners?q=баев&limit=50&offset=0
```

Limits:

```text
limit default: 50
limit max: 200
offset default: 0
```

Implementation note: SQL Server 2000 does not support `OFFSET/FETCH` or `ROW_NUMBER()`, so pagination is implemented with bounded `TOP` queries and stable ordering by `_PARTNER.N_USER`, `_PARTNER.NAME_USER`, `_PARTNER.NAMEP_USER`.

## Response

Suggested JSON:

```json
{
  "ok": true,
  "items": [
    {
      "id": "БОНД АНН",
      "shortName": "БОНД АНН",
      "name": "Бондаренко Ганна Ігорівна ФОП",
      "type": "К",
      "typeLabel": "Покупатель",
      "bankName": "",
      "bankAccount": "",
      "bankCode": "",
      "bankCity": "",
      "phone": "",
      "city": "",
      "raw": {
        "nUser": "БОНД АНН",
        "namePUser": ""
      }
    }
  ],
  "total": 1,
  "limit": 50,
  "offset": 0
}
```

Current implementation reads only `_PARTNER`. Until `_PARTNER_PL` relationship is confirmed, requisites not present in `_PARTNER` are returned as empty strings:

- `phone`
- `city`

TODO: confirm `_PARTNER_PL` links and map phone/city without changing the existing response field names.

Bank fields are filled from `_PARTNER`:

- `bankName` = `BANK_USER`
- `bankAccount` = `SCT_B_USER`
- `bankCode` = `COD_B_USER`
- `bankCity` = `TOWNB_USER`

Partner identity fields are filled from `_PARTNER` as follows:

- `id` = `N_USER`
- `shortName` = `N_USER`
- `name` = `NAME_USER`
- `raw.namePUser` = `NAMEP_USER`

Important: according to `Структура7.doc`, `_PARTNER.N_USER` is the unique short organization name and is copied by Folio into `SCL_NAKL.BRIEFORG` and `SCL_MOVE.ORG_PREDM`. `_PARTNER.NAME_USER` is the full organization name for `SCL_NAKL.ORGANIZNKL` / printed and screen forms. `_PARTNER.NAMEP_USER` is the payment-document name and may be empty; it is not the short name.

## Minimum Required Fields

For the first Woo integration step, each item must contain:

```json
{
  "id": "_PARTNER.N_USER, stable Folio short organization key",
  "shortName": "_PARTNER.N_USER for BRIEFORG / payerShortName",
  "name": "_PARTNER.NAME_USER, full client name for ORGANIZNKL / payerName",
  "type": "MY_ORGANIZ",
  "typeLabel": "human-readable type label"
}
```

## Requirements

- Read-only endpoint.
- Protected by the same admin token/auth mechanism as other `/admin/...` endpoints.
- Must not return an unbounded list.
- Must preserve Cyrillic organization type values exactly.
- If `_PARTNER_PL` relationships are not yet confirmed, return additional requisites as empty strings and add a TODO in code/docs.
- Response field names should stay stable because Woo will store selected partner data in user meta.

## Intended Woo Usage

Woo user profile will have a Folio client selector near user identity fields.

After selection, Woo will save user meta similar to:

```text
_folio_partner_id
_folio_partner_short_name
_folio_partner_name
_folio_partner_type
```

When building Folio account preview JSON, Woo will read the selected client from user meta instead of guessing by billing name or company.

## Related Docs

```text
docs/business/01_ACCOUNT.md
docs/api/FOLIO_ACCOUNT_JS_API.md
docs/00_DATABASE_CATALOG.md
```


## Manager customer registration (2026-10-06)

`GET /admin/folio/partners/registration?id=<exact N_USER>` reads one client of type
П/Д/К/H. No writes, joins or default Internet-client substitutions occur.
This route is disabled (503) until `folio.customer-import.token` is configured
with at least 32 characters (environment `FOLIO_CUSTOMER_IMPORT_TOKEN`). It checks
`X-Auth-Token` with a constant-time comparison before querying contacts; absent or
wrong tokens return 401. Use HTTPS or the existing private server transport.
The WordPress server sends its configured Lavka API token; never put it in browser
JavaScript, URLs, Git or logs. Successful responses use `Cache-Control: no-store`.
Existing partner search remains unchanged and does not gain contact fields.

Response: `id`, `name`, `type`, `email`, `phone`, `alternatePhone`, `address`,
`postcode`, `deliveryAddress`, `discountPercent`, `bankCity`, `contactType`, `note`, `additionalInfo`. Sources are `_PARTNER.N_USER`,
`NAME_USER`, `MY_ORGANIZ`, `EMAIL_USER`, `TEL1_USER`, `TEL2_USER`, `ADRES_USER`,
`INDEX_USER`, `DOST_ADRESS`, `SKIDKAPRCNT`, `TOWNB_USER`, `CP_2`, `PRIMECH`, `INFORM_PAR`. Whitespace is trimmed; absent fields are
null. Unknown/non-customer keys return 404; empty/overlong keys return 400.

Evidence: column snapshot `01_live_colums_corect.rpt` (2026-08-11) and manufacturer
Структура7 field descriptions, with local DAO/controller tests. Current production
contact completeness has not been measured. This route has not been deployed by
this task. No schema migration is required.

There is no verified price-contract, first/last-name or customer-city field in
this mapping. Owner instruction on 2026-10-06 explicitly allows bank city as an
editable billing/shipping city default and UA for both countries. `contactType`
is the literal CP_2 reference shown above the Woo role selector, not an automatic
contract mapping. `note` and multiline `additionalInfo` feed a manager-only
customer note; do not expose them through public biographies or email. WordPress managers review these fields and explicitly select an
existing site role/contract. Personal discounts are shown as a review hint, not
silently added to role pricing. Java preserves source address fields; WordPress initially copies billing address
and postcode into shipping by owner request. No Nova Poshta branch is inferred.

Consumer: WordPress `pc-order-import-export/inc/FolioCustomerImport.php` and
`FolioCustomerImportUi.php`. It stages private owner-scoped jobs, validates each
source again before creating a new account, skips existing email/Folio links,
and optionally sends password-setting links after saving the account. Manager
instructions live in the WordPress `docs/MANAGER_CUSTOMER_GUIDE_UK.md`.
Deployment: configure the matching server token and release Java first, then the
WordPress consumer. Rollback may remove this route without changing Folio data;
WordPress will report registration data unavailable. Tests:
`FolioRegistrationTest`, `FolioRegistrationDaoTest` (no database connection).

### Import page email checks (2026-10-06)

Read-only `POST /admin/folio/partners/registration-emails` accepts a JSON array
of 1..25 exact customer keys, each at most 8 characters. It uses the same
`X-Auth-Token`/503/401 protection as registration and returns `Cache-Control:
no-store`. Response is an object mapping found customer keys to trimmed email
strings or null for no email. Unknown/non-customer keys are omitted. Invalid
size/keys return 400. One parameterized `_PARTNER` query reads only N_USER and
EMAIL_USER, limited to П/Д/К/H; no writes, bank fields or notes are returned.
The WordPress importer compares these emails against Woo accounts, separately
from Folio-key mappings. This route does not change ordinary partner search.
Deploy Java before WordPress; on failure the UI must show unknown email status,
not imply absence of an account. Controller/DAO tests cover access, bounds,
query parameters and the distinction between missing email and missing key.
