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

Implementation note: SQL Server 2000 does not support `OFFSET/FETCH` or `ROW_NUMBER()`, so pagination is implemented with bounded `TOP` queries and stable ordering by `_PARTNER.NAMEP_USER`, `_PARTNER.NAME_USER`, `_PARTNER.N_USER`.

## Response

Suggested JSON:

```json
{
  "ok": true,
  "items": [
    {
      "id": "123",
      "shortName": "БАЕВСКАЯ",
      "name": "Баевская Людмила Александровна",
      "type": "К",
      "typeLabel": "Покупатель",
      "bankName": "",
      "bankAccount": "",
      "bankCode": "",
      "bankCity": "",
      "phone": "",
      "city": "",
      "raw": {
        "nUser": "123"
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

## Minimum Required Fields

For the first Woo integration step, each item must contain:

```json
{
  "id": "stable Folio primary key",
  "shortName": "short client name for BRIEFORG / payerShortName",
  "name": "full client name for ORGANIZNKL / payerName",
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
