# Folio Account API для JavaScript

Базовый путь:

```text
/admin/folio/accounts
```

API работает только с документом типа `СЧЕТ`.

## 1. Создать счёт

```http
POST /admin/folio/accounts
Content-Type: application/json
```

```json
{
  "externalRequestId": "7ec24df3-b03d-4a3d-a104-d459658451b7",
  "documentNumber": "123456831",
  "documentDate": "2026-07-21T15:30:00",
  "warehouseId": 1,
  "operationType": "СЧЕТ",
  "partnerId": null,
  "comment": "Создано из WooCommerce",
  "items": [
    {
      "sku": "ABC-001",
      "quantity": 2,
      "price": 10.0
    }
  ]
}
```

Важно: в текущей базе ФОЛИО `SCL_NAKL.N_PLAT_POR` имеет тип `float NOT NULL`, поэтому `documentNumber` сейчас должен быть числовым. Номер вида `WEB-2026-00001` нельзя писать в это поле напрямую; для внешнего текстового номера нужно отдельное подтверждённое поле или интеграционная таблица.

Текущий ответ реализации:

```http
HTTP/1.1 201 Created
```

```json
{
  "ok": true,
  "account": {
    "documentId": 752328,
    "documentNumber": "WEB-2026-00001",
    "documentDate": "2026-07-21T15:30:00",
    "operationType": "СЧЕТ",
    "warehouseId": 1,
    "totalAmount": 20.0,
    "active": true,
    "items": [
      {
        "recno": 105501,
        "lineNumber": 1,
        "sku": "ABC-001",
        "warehouseId": 1,
        "quantity": 2,
        "price": 10.0,
        "amount": 20.0
      }
    ]
  }
}
```

Примечание: финальное ТЗ хочет имена `success`, `id`, `lineId`, `status`, `totalQuantity`, `warnings` и stock-блок. Это ещё не приведено к финальному контракту, чтобы не ломать уже проверенный GET без отдельного решения.

## 1.1. Реквизиты шапки, которые нужно добавить в расширенный JS-контракт

По Excel-снимку `/Volumes/BackUp/Nakl_field.xls` счёт, созданный минимальным API (`753538`), записался в SQL, но не появился в реестре ФОЛИО. Видимый счёт (`753529`) и новый ручной счёт (`753546`) имеют намного более полную шапку `SCL_NAKL`.

Следующий вариант request нужно расширять не “всеми колонками подряд”, а подтверждёнными бизнес-реквизитами:

```json
{
  "externalRequestId": "7ec24df3-b03d-4a3d-a104-d459658451b7",
  "documentNumber": "123456831",
  "documentDate": "2026-07-22T00:00:00",
  "controlDate": "2026-07-27",
  "warehouseId": 7,
  "operationType": "СЧЕТ",
  "folioOperationKind": "*ПРЕДОПЛАТ",
  "payerName": "Баевская Людмила Александровна",
  "receiverName": "CLASSIC",
  "payerShortName": "БАЕВСКАЯ",
  "folioUser": "coboss",
  "sourceInfo": "КиОПТ сборка",
  "additionalInfo": "Инф Тест",
  "priceContractType": "20%",
  "notCash": true,
  "accountingEnabled": true,
  "returnFlag": false,
  "comment": "тест",
  "items": [
    {
      "sku": "ABC-001",
      "quantity": 2,
      "price": 10.0
    }
  ]
}
```

Предварительное соответствие полей:

| JS-поле | SCL_NAKL | Зачем |
|---|---|---|
| `controlDate` | `CONTRLDATE` | контрольный срок счёта |
| `folioOperationKind` | `VID_DOC` | тип операции из справочника ФОЛИО; не фиксировать константой |
| `payerName` | `ORGANIZNKL` | плательщик/организация в реестре и форме |
| `receiverName` | `MY_ORGANIZ` | получатель/моя организация |
| `payerShortName` | `BRIEFORG` | краткое имя/источник |
| `folioUser` | `FAMILY`, возможно `WHO_CORR` | пользователь/автор ФОЛИО |
| `sourceInfo` | `L_CP1_PLAT` | источник информации |
| `additionalInfo` | `L_CP2_PLAT` | дополнительная информация |
| `priceContractType` | `CONTR_POR` | вид контракта цены из справочника |
| `notCash` | `NOT_NAL` | признак безнала/наличности |
| `accountingEnabled` | `STND_UCHET` | влияет на учётность/резервирование |
| `returnFlag` | `VOZVRAT_PR` | признак возврата, обычно `0` |

Поля, которые лучше держать в конфигурации backend, а не просить у JS каждый раз:

- `TYPE_DOC = 'С'` — кириллическая `С`;
- `NALOG_POR = 'НДС'`;
- `PRCNT_POR = 20`;
- `VALUTROUBL = 1`;
- `COD_VALUT = 4`, если валюта всегда та же;
- `PRCN2_POR = 0`;
- `OPLATA_SCH = 0`;
- `CHAST_OPLT = 0`;
- `OTMETKA = 0`;
- `IS_NALPROD = 138`;
- `NDS_TORGN = 0`;
- `CREATEDATE = now()`;
- `ID_SCLAD = warehouseId`.

## 2. Получить счёт

```http
GET /admin/folio/accounts/{unicumNum}
```

Текущий ответ:

```json
{
  "documentId": 752328,
  "documentNumber": "WEB-2026-00001",
  "documentDate": "2026-07-21T15:30:00",
  "operationType": "СЧЕТ",
  "warehouseId": 1,
  "totalAmount": 20.0,
  "active": true,
  "items": [
    {
      "recno": 105501,
      "lineNumber": 1,
      "sku": "ABC-001",
      "warehouseId": 1,
      "quantity": 2,
      "price": 10.0,
      "amount": 20.0
    }
  ]
}
```

## 3. Изменить количество строки

Основной путь по полному ТЗ:

```http
PATCH /admin/folio/accounts/{unicumNum}/items/{recno}
Content-Type: application/json
```

Совместимый старый путь также оставлен:

```http
PATCH /admin/folio/accounts/{unicumNum}/items/{recno}/quantity
```

Запрос:

```json
{
  "quantity": 5
}
```

Ответ текущей реализации возвращает весь обновлённый счёт в wrapper:

```json
{
  "ok": true,
  "account": {
    "documentId": 752328,
    "items": []
  }
}
```

## 4. Добавить строку

```http
POST /admin/folio/accounts/{unicumNum}/items
Content-Type: application/json
```

```json
{
  "sku": "ABC-004",
  "quantity": 2,
  "price": 12.5
}
```

Успех:

```http
HTTP/1.1 201 Created
```

Ответ текущей реализации возвращает весь обновлённый счёт в wrapper.

## 5. Удалить строку

```http
DELETE /admin/folio/accounts/{unicumNum}/items/{recno}
```

Алгоритм:

1. блокирует строку счёта;
2. возвращает резерв в `SCL_ARTC.REZ_KOLCH`;
3. удаляет строку `SCL_MOVE`;
4. пересчитывает сумму `SCL_NAKL.SUM_POR`.

Ответ текущей реализации возвращает весь обновлённый счёт в wrapper.

## 6. Удаление всего счёта

По полному ТЗ физическое удаление всего счёта запрещено до отдельного эксперимента.

```http
DELETE /admin/folio/accounts/{unicumNum}
```

Текущий ответ:

```http
HTTP/1.1 501 Not Implemented
```

```json
{
  "success": false,
  "error": {
    "code": "ACCOUNT_DELETE_NOT_IMPLEMENTED",
    "message": "Удаление всего счёта отключено до подтверждения поведения ФОЛИО"
  },
  "accountId": 752328
}
```

Есть старый служебный endpoint:

```http
POST /admin/folio/accounts/{unicumNum}/cancel
```

Он делает мягкую отмену: возвращает резерв и ставит `STND_UCHET = 0`. Не считать его финальным API удаления, пока не подтверждено поведение ФОЛИО.

## Ошибки

Текущая реализация использует общий `GlobalExceptionHandler`, поэтому формат пока не полностью совпадает с финальным JS-контрактом.

Основные случаи:

- `400` — validation error;
- `404` — счёт или строка не найдены;
- `409` — конфликт: недостаточный остаток, дубль SKU, неподдержанный тип операции;
- `501` — удаление всего счёта не реализовано.

## Важные правила для JS

- `externalRequestId` обязателен и должен быть стабильным при повторе запроса.
- Денежные значения отправлять числом или строкой, без `double`-логики на стороне Java.
- `documentNumber` сейчас должен быть числовым, потому что в ФОЛИО это `SCL_NAKL.N_PLAT_POR float`.
- Для создания использовать только `operationType: "СЧЕТ"`.
- В ФОЛИО `SCL_NAKL.TYPE_DOC` для счёта пишется как подтверждённая константа `С` — кириллическая буква Es `U+0421`, не латинская `C`; JS её не передаёт.
- В строках ФОЛИО `SCL_MOVE.TYPDOCM_PR` тоже пишется как кириллическая `С`.
- `VID_DOC` — это не технический тип документа, а вид операции из справочника ФОЛИО. Его нельзя безопасно фиксировать как `*РАЗОВАЯ`: в проверенных счетах встречаются `*РАЗОВАЯ`, `*ПЕРЕМЕЩЕНИЕ`, `*ПРЕДОПЛАТ`.
- После `409 INSUFFICIENT_AVAILABLE_STOCK` не повторять тот же запрос без изменения количества/остатков.
