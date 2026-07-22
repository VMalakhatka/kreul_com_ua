# Java API для счетов ФОЛИО

## Endpoints

Базовый путь:

```text
/admin/folio/accounts
```

- `POST /admin/folio/accounts` — создать счёт.
- `GET /admin/folio/accounts/{documentId}` — получить счёт по `SCL_NAKL.UNICUM_NUM`.
- `PATCH /admin/folio/accounts/{documentId}/items/{recno}/quantity` — изменить количество строки.
- `POST /admin/folio/accounts/{documentId}/items` — добавить строку.
- `DELETE /admin/folio/accounts/{documentId}/items/{recno}` — удалить строку.
- `POST /admin/folio/accounts/{documentId}/cancel` — отменить счёт.

## Границы реализации

- Реализован только документ типа `СЧЕТ`.
- `SCL_ADDN`, `SCL_NS`, `SCL_NR` не модифицируются.
- При создании счёта `KON_KOLCH` не меняется.
- При создании/добавлении/увеличении строки уменьшается `SCL_ARTC.REZ_KOLCH`.
- При удалении строки/уменьшении количества/отмене счёта резерв возвращается.
- Отмена счёта сделана мягко: `STND_UCHET = 0` в `SCL_NAKL` и `SCL_MOVE`.

## Служебная таблица идемпотентности

Перед использованием `POST /admin/folio/accounts` нужна отдельная интеграционная таблица в MSSQL.
Она не является штатной таблицей ФОЛИО.

```sql
CREATE TABLE dbo.LAVKA_FOLIO_ACCOUNT_REQUESTS (
    EXTERNAL_REQUEST_ID VARCHAR(64) NOT NULL PRIMARY KEY,
    UNICUM_NUM NUMERIC(18, 0) NOT NULL,
    CREATED_AT DATETIME NOT NULL DEFAULT GETDATE()
);
```

`externalRequestId` должен быть стабильным UUID/id со стороны WordPress/WooCommerce.

## Настройки

```properties
lavka.folio.accounts.allowed-operation-types=СЧЕТ
lavka.folio.accounts.document-type=СЧЕТ
```
