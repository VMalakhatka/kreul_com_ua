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
      "price": 10.0,
      "currencyPrice": 0,
      "currencyAmount": 0,
      "retailAmount": 20.0
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

## 1.0.1. Создать счета из Woo order с распределением по складам

Высокоуровневый endpoint для payload-а из WooCommerce:

```http
POST /admin/folio/order-accounts
Content-Type: application/json
```

Источник входного контракта Woo:

```text
/Users/admin/Local Sites/paint/app/public/docs/FOLIO_ORDER_JSON_CONTRACT.md
```

Назначение:

- Java принимает один Woo order;
- Woo не разбивает заказ на несколько документов сам;
- Java распределяет строки по `items[].allocations[].folio_warehouses` с учетом `priority`;
- для каждого выбранного склада создается отдельный счет ФОЛИО;
- если свободного остатка не хватило, недостающее количество попадает в отдельный неучитываемый счет;
- если `folio_account_header.documentNumber` пустой, Java генерирует следующий числовой `SCL_NAKL.N_PLAT_POR`.
- в preview-режиме `document_id` и `document_number` рассчитываются как при настоящем проходе, но не записываются и не резервируются; если между preview и реальным запуском в ФОЛИО появится другой документ, фактический номер может сдвинуться.

Режимы:

| Поле | Поведение |
|---|---|
| `preview_only=true` | только расчет split-а и ответа, без записи в ФОЛИО; возвращает прогнозные `document_id` / `document_number`, но не резервирует их |
| `preview_only=false` | создание документов через проверенный низкоуровневый `POST /admin/folio/accounts` |
| `woo_order.status=processing` | обычный учитываемый счет, резерв уменьшается |
| `woo_order.status=pc-draft` | один неучитываемый счет на приоритетном складе, резерв не меняется |
| `woo_order.status=completed` | сейчас отклоняется: это расходная накладная, а endpoint создает только счета |

Поле `folio_account_header.accountingEnabled` оставлено для совместимости с существующим JSON, но при наличии `woo_order.status` режим определяется статусом Woo order.

Важные правила текущей реализации:

- поддерживается `schema_version = "folio-order-preview/v1"`;
- входные root-поля принимаются в snake_case как в Woo-контракте;
- `folio_account_header` повторяет Woo-контракт и не содержит `items[]`; товарные строки берутся только из root `items[]`;
- для реальных учитываемых счетов остаток распределяется по складам-кандидатам от меньшего `priority` к большему;
- одинаковый SKU внутри одного складского счета объединяется в одну строку, если цена совпадает;
- одинаковый SKU с разной ценой в одном складском счете сейчас отклоняется как `duplicate_sku_different_price`;
- длинный `folio_account_header.comment` обрезается до 5 символов, потому что он пишется в короткое поле `SCL_NAKL.DOPN_SCHET`; подробный текст заказа нужно передавать через `additionalInfo` / `deliveryInfo`.

Минимальный ответ:

```json
{
  "ok": true,
  "preview_only": false,
  "woo_order_id": 116873,
  "documents": [
    {
      "document_id": 753600,
      "document_number": "123456840",
      "document_type": "account",
      "document_status": "created",
      "folio_warehouse_id": 7,
      "accounting_enabled": true,
      "source_external_request_id": "woo-116873:wh:7",
      "document_created_at": "2026-07-23T12:34:56",
      "items": [
        {
          "order_item_id": 2477,
          "sku": "CR-CE0900056027",
          "quantity": 1,
          "price": 130.0,
          "amount": 130.0,
          "folio_warehouse_id": 7,
          "allocation_status": "allocated"
        }
      ]
    }
  ],
  "warnings": [],
  "errors": []
}
```

Если часть товара не хватило:

- учитываемые документы создаются только на реально доступное количество;
- остаток создается отдельным документом `document_type = "missing_stock_account"`;
- у этого документа `accounting_enabled = false`;
- в `warnings` добавляется `INSUFFICIENT_AVAILABLE_STOCK`.

## 1.1. Расширенные реквизиты шапки

По Excel-снимку `/Volumes/BackUp/Nakl_field.xls` счёт, созданный минимальным API (`753538`), записался в SQL, но не появился в реестре ФОЛИО. Видимый счёт (`753529`) и новый ручной счёт (`753546`) имеют намного более полную шапку `SCL_NAKL`.

Текущий request расширен не “всеми колонками подряд”, а подтверждёнными бизнес-реквизитами:

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
  "payerCity": "Чернигов",
  "directorName": "Дир Тест",
  "accountantName": "Гл бух Теые",
  "payerPhone": "+380636020525",
  "deliveryInfo": "НП,Чернигов,отд.14, Укринстумент 30731947,оплата безнал, тел.0636020525",
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

Required для Swagger/валидации:

- `controlDate`
- `folioOperationKind`
- `payerName`
- `receiverName`
- `payerShortName`
- `folioUser`
- `sourceInfo`
- `additionalInfo`
- `notCash`
- `accountingEnabled`
- `returnFlag`

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

Дополнительные реквизиты `SCL_ADDN` создаются в той же транзакции:

| JS-поле | SCL_ADDN | Зачем |
|---|---|---|
| `payerCity` | `L_TOWN_POR` | город плательщика |
| `directorName` | `DIRCT_POR` | директор |
| `accountantName` | `FINDIR_POR` | главный бухгалтер |
| `payerPhone` | `L_TEL1_PLA` | телефон плательщика |
| `sourceInfo` | `L_CP1_PLAT` | дублируется в `SCL_ADDN` как в ручном счёте |
| `additionalInfo` | `L_CP2_PLAT` | дублируется в `SCL_ADDN` как в ручном счёте |
| `deliveryInfo` | `G_POL_POR` | информация доставки/получателя |

Технические поля `SCL_ADDN.D_PR_DOC`, `SCL_ADDN.POLSC_DATE`, `SCL_ADDN.NLG_REG` заполняются backend по образцу ручного счёта.

## 1.2. Какие поля являются справочными

Часть значений JS передаёт как выбранные пользователем бизнес-реквизиты, но сами значения должны существовать или быть согласованы со справочниками ФОЛИО.

Для выбора клиента WooCommerce использовать:

```http
GET /admin/folio/partners?q=баев&types=П,Д,К&limit=50&offset=0
```

Из выбранного партнёра брать:

- `id` -> Woo user meta `_folio_partner_id`
- `shortName` -> `payerShortName`
- `name` -> `payerName`
- `type` -> Woo user meta `_folio_partner_type`

Важно: для `_PARTNER` поле `id` и `shortName` в нашем API сейчас оба равны `_PARTNER.N_USER`. По документации ФОЛИО это уникальное краткое имя организации, которое переносится в `SCL_NAKL.BRIEFORG` и `SCL_MOVE.ORG_PREDM`. Поле `_PARTNER.NAMEP_USER` — имя для платежных документов; оно может быть пустым и не используется как `payerShortName`.

| JS-поле | Таблица/справочник ФОЛИО | Как используется |
|---|---|---|
| `warehouseId` | `SCLAD_R.ID_SCLAD` | проверяется как существующий склад; пишется в `SCL_NAKL.ID_SCLAD`, `SCL_MOVE.ID_SCLAD`, используется для поиска `SCL_ARTC` |
| `sku` | `SCL_ARTC.COD_ARTIC` + `SCL_ARTC.ID_SCLAD` | товарная строка должна существовать на складе |
| `folioOperationKind` | `VID_OPER`, поле `VID_DOC` | пишется в `SCL_NAKL.VID_DOC` и `SCL_MOVE.VID_DOC`; ФОЛИО может добавлять значение вручную, но для API лучше передавать справочное значение |
| `priceContractType` | `_KONTRCT`, поле `CONTR_POR`/`CONTRACT_N` | пишется в `SCL_NAKL.CONTR_POR` и переносится в `SCL_MOVE.CONTRACT_N` |
| `payerName` | `_PARTNER.NAME_USER`, полное имя внешней организации | пишется в `SCL_NAKL.ORGANIZNKL` |
| `payerShortName` | `_PARTNER.N_USER`, краткое уникальное имя внешней организации | пишется в `SCL_NAKL.BRIEFORG`, переносится в `SCL_MOVE.ORG_PREDM` |
| `receiverName` | справочник моих организаций/получателей ФОЛИО | пишется в `SCL_NAKL.MY_ORGANIZ` |
| `sourceInfo` | `_RECLAMA`, источник информации | пишется в `SCL_NAKL.L_CP1_PLAT` и `SCL_ADDN.L_CP1_PLAT` |
| `additionalInfo` | `DOP_INF`, дополнительная информация | пишется в `SCL_NAKL.L_CP2_PLAT` и `SCL_ADDN.L_CP2_PLAT` |
| `folioUser` | пользователи ФОЛИО | пишется в `SCL_NAKL.FAMILY` и `SCL_NAKL.WHO_CORR` |

Поля строки, которые JS не передаёт и backend берёт из `SCL_ARTC`:

| SCL_MOVE | Источник | Правило |
|---|---|---|
| `BALL1` | `SCL_ARTC.BALL1` | `SCL_ARTC.BALL1 * quantity` |
| `BALL2` | `SCL_ARTC.BALL2` | `SCL_ARTC.BALL2 * quantity` |
| `BALL3` | `SCL_ARTC.BALL3` | `SCL_ARTC.BALL3 * quantity` |
| `BALL4` | `SCL_ARTC.BALL4` | `SCL_ARTC.BALL4 * quantity` |
| `BALL5` | `SCL_ARTC.BALL5` | `SCL_ARTC.BALL5 * quantity` |

Поля строки, которые backend переносит из шапки:

| SCL_MOVE | Источник |
|---|---|
| `ORG_PREDM` | `SCL_NAKL.BRIEFORG` |
| `NUMDOCM_PR` | `SCL_NAKL.N_PLAT_POR` |
| `NUMDCM_DOP` | `SCL_NAKL.DOPN_SCHET` |
| `NOT_NAL` | `SCL_NAKL.NOT_NAL` |
| `CONTRACT_N` | `SCL_NAKL.CONTR_POR` |
| `VALUTROUBL` | `SCL_NAKL.VALUTROUBL` |
| `OPLATA_SCH` | `SCL_NAKL.OPLATA_SCH` |
| `VOZVRAT_PR` | `SCL_NAKL.VOZVRAT_PR` |
| `OTMETKA` | `SCL_NAKL.OTMETKA` |

Поля строки, которые можно передать на уровне item:

| Item-поле | SCL_MOVE | Примечание |
|---|---|---|
| `currencyPrice` | `VALUT_CENA` | если не передано — `0` |
| `currencyAmount` | `SUM_VALUT` | если не передано — `0` |
| `retailAmount` | `SUM_ROZN` | если не передано — `price * quantity` |

Ограничения длины строк в текущей базе `Paint_Ua`:

| JS-поле | SCL_NAKL | Максимум |
|---|---|---:|
| `comment` | `DOPN_SCHET` | 5 |
| `folioOperationKind` | `VID_DOC` | 20 |
| `payerName` | `ORGANIZNKL` | 50 |
| `receiverName` | `MY_ORGANIZ` | 50 |
| `payerShortName` | `BRIEFORG` | 8 |
| `folioUser` | `FAMILY`, `WHO_CORR` | 20 |
| `sourceInfo` | `L_CP1_PLAT` | 30 |
| `additionalInfo` | `L_CP2_PLAT` | 30 |
| `priceContractType` | `CONTR_POR` | 10 |
| `payerCity` | `SCL_ADDN.L_TOWN_POR` | 28 |
| `directorName` | `SCL_ADDN.DIRCT_POR` | 75 |
| `accountantName` | `SCL_ADDN.FINDIR_POR` | 75 |
| `payerPhone` | `SCL_ADDN.L_TEL1_PLA` | 20 |
| `deliveryInfo` | `SCL_ADDN.G_POL_POR` | 150 |

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

Ответ возвращает расширенный объект счёта: шапку `SCL_NAKL`, дополнительные реквизиты `SCL_ADDN` и строки `SCL_MOVE`. Он должен содержать не меньше данных, чем передаётся при создании.

```json
{
  "documentId": 752328,
  "documentNumber": "123456839",
  "documentDate": "2026-07-22T00:00:00",
  "operationType": "СЧЕТ",
  "warehouseId": 7,
  "totalAmount": 130.0,
  "comment": "TEST",
  "controlDate": "2026-07-27",
  "folioOperationKind": "*ПРЕДОПЛАТ",
  "payerName": "БАЕВСКАЯ",
  "receiverName": "CLASSIC",
  "payerShortName": "БАЕВСКАЯ",
  "folioUser": "coboss",
  "sourceInfo": "КиОПТ",
  "additionalInfo": "Тест",
  "priceContractType": "ПАРТНЁР",
  "notCash": true,
  "accountingEnabled": true,
  "returnFlag": false,
  "currencyAmount": 0.0,
  "retailAmount": 130.0,
  "payerCity": "Чернигов",
  "directorName": "Дир Тест",
  "accountantName": "Гл бух Теые",
  "payerPhone": "+380636020525",
  "deliveryInfo": "НП,Чернигов,отд.14...",
  "createdDate": "2026-07-22T15:46:20.030",
  "correctionDate": null,
  "correctedBy": "coboss",
  "active": true,
  "items": [
    {
      "recno": 8991059,
      "lineNumber": 1,
      "sku": "CR-CE0900056027",
      "warehouseId": 7,
      "quantity": 1,
      "price": 130.0,
      "amount": 130.0,
      "organizationShortName": "БАЕВСКАЯ",
      "documentNumber": "123456839",
      "documentNumberSuffix": "TEST",
      "typeDoc": "С",
      "notCash": true,
      "priceContractType": "ПАРТНЁР",
      "currencyPrice": 0.871,
      "currencyCode": "4",
      "currencyAmount": 0.87,
      "valutaRouble": true,
      "retailAmount": 130.0,
      "folioOperationKind": "*ПРЕДОПЛАТ",
      "ball1": 34070000.0,
      "ball2": 2.0,
      "ball3": 56.0,
      "ball4": 0.0,
      "ball5": 1.0
    }
  ]
}
```

## 2.1. Список счетов

```http
GET /admin/folio/accounts?dateFrom=2026-07-22&dateTo=2026-07-22&payerName=БАЕВСКАЯ&warehouseIds=7
```

Фильтры:

| Query param | Обяз. | Значение |
|---|---:|---|
| `dateFrom` | нет | дата документа с, формат `YYYY-MM-DD` |
| `dateTo` | нет | дата документа по, формат `YYYY-MM-DD`; backend ищет до следующего дня, чтобы не терять записи со временем |
| `payerName` | нет | поиск по `SCL_NAKL.ORGANIZNKL` или `SCL_NAKL.BRIEFORG` |
| `warehouseIds` | нет | один или несколько складов; в Swagger можно передавать `warehouseIds=7&warehouseIds=8` или `warehouseIds=7,8` |

Ответ — краткие карточки для списка:

```json
[
  {
    "documentId": 753568,
    "documentNumber": "123456839",
    "documentDate": "2026-07-22T00:00:00",
    "operationType": "СЧЕТ",
    "warehouseId": 7,
    "totalAmount": 130.0,
    "payerName": "БАЕВСКАЯ",
    "receiverName": "CLASSIC",
    "payerShortName": "БАЕВСКАЯ",
    "sourceInfo": "КиОПТ",
    "additionalInfo": "Тест",
    "folioOperationKind": "*ПРЕДОПЛАТ",
    "controlDate": "2026-07-27",
    "active": true,
    "createdDate": "2026-07-22T15:46:20.030"
  }
]
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
- `documentDate` можно передавать как `LocalDateTime`, но при записи в ФОЛИО время нормализуется до `00:00:00`. Реестр ФОЛИО для отбора по дню ориентируется на дату без времени.
- Для создания использовать только `operationType: "СЧЕТ"`.
- В ФОЛИО `SCL_NAKL.TYPE_DOC` для счёта пишется как подтверждённая константа `С` — кириллическая буква Es `U+0421`, не латинская `C`; JS её не передаёт.
- В строках ФОЛИО `SCL_MOVE.TYPDOCM_PR` тоже пишется как кириллическая `С`.
- `VID_DOC` — это не технический тип документа, а вид операции из справочника ФОЛИО. Его нельзя безопасно фиксировать как `*РАЗОВАЯ`: в проверенных счетах встречаются `*РАЗОВАЯ`, `*ПЕРЕМЕЩЕНИЕ`, `*ПРЕДОПЛАТ`.
- После `409 INSUFFICIENT_AVAILABLE_STOCK` не повторять тот же запрос без изменения количества/остатков.
