# Документы клиента ФОЛИО: контракт для WordPress/WooCommerce

## Назначение

API позволяет личному кабинету клиента:

- показать список активных счетов, расходных накладных и платежей ФОЛИО;
- открыть документ и увидеть его реквизиты и товарные строки;
- подготовить повторный заказ из счёта или расходной накладной;
- загрузить выбранные позиции в корзину либо в черновик заказа на стороне WooCommerce.

API работает **только на чтение**. Он не исправляет и не создаёт документы ФОЛИО, не меняет остатки и оплаты.

## Безопасность интеграции

Маршрут Java находится в административной зоне. Публичный браузер клиента не должен вызывать его напрямую и не должен сам передавать произвольный `partnerShortName`.

Правильная цепочка:

1. клиент авторизуется в WordPress;
2. WordPress определяет привязанный к пользователю идентификатор ФОЛИО;
3. WordPress вызывает Java от имени сервера;
4. Java в каждом запросе проверяет документ одновременно по техническому ID и точному клиенту;
5. WordPress возвращает клиенту безопасную часть ответа.

Идентификатор клиента — точное значение `_PARTNER.N_USER`, максимум 8 символов. Для складских документов оно проверяется по `SCL_NAKL.BRIEFORG`, для платежей — по `SCL_PLAT.ORG_PREDM`.

## 1. Список документов

```http
GET /admin/folio/customer-documents
```

### Параметры

| Параметр | Обязательный | Правило |
|---|---:|---|
| `partnerShortName` | да | точное `_PARTNER.N_USER`; WordPress получает из профиля авторизованного клиента |
| `dateFrom` | нет | `YYYY-MM-DD`; по умолчанию начало последних 12 месяцев |
| `dateTo` | нет | `YYYY-MM-DD`; включительно, по умолчанию текущая бизнес-дата Java |
| `types` | нет | `ACCOUNT`, `EXPENSE`, `PAYMENT` через запятую; по умолчанию `all` |
| `limit` | нет | 1–100, по умолчанию 50 |
| `cursor` | нет | непрозрачный `nextCursor` из предыдущего ответа |

Максимальный период одного запроса — 366 дней. Для более ранней истории интерфейс должен запрашивать соседние годовые интервалы.

Пример:

```http
GET /admin/folio/customer-documents?partnerShortName=БОНД%20АНН&dateFrom=2026-01-01&dateTo=2026-08-14&types=ACCOUNT,EXPENSE,PAYMENT&limit=50
```

### Пример ответа

```json
{
  "ok": true,
  "partner": {
    "shortName": "БОНД АНН",
    "name": "Бондаренко Анна"
  },
  "filters": {
    "dateFrom": "2026-01-01",
    "dateTo": "2026-08-14",
    "types": ["ACCOUNT", "EXPENSE", "PAYMENT"],
    "limit": 50
  },
  "documents": [
    {
      "documentType": "EXPENSE",
      "documentId": 751193,
      "documentNumber": "64471",
      "documentNumberSuffix": "/0626",
      "documentDate": "2026-06-11T13:34:13.103",
      "totalAmount": 9284.34,
      "currencyAmount": null,
      "currencyCode": null,
      "warehouseId": 7,
      "accounted": true,
      "nonCash": false,
      "returnDocument": false,
      "paymentDirectionRaw": null,
      "operationKind": "*РОЗНИЦА",
      "additionalInfo": "не найдено Ермолаева",
      "lineCount": 12,
      "allocatedAmount": null,
      "canRepeatOrder": true,
      "source": "ACTIVE_LEDGER"
    }
  ],
  "nextCursor": null,
  "hasMore": false,
  "warnings": [
    {
      "code": "ACTIVE_LEDGER_ONLY",
      "message": "Archived Folio documents are not included",
      "details": {}
    }
  ]
}
```

Документы отсортированы по дате от новых к старым. Если `hasMore=true`, следующий запрос должен повторить те же фильтры и передать `cursor=<nextCursor>`. Не разбирать и не изменять cursor на фронте.

`additionalInfo` для `ACCOUNT` и `EXPENSE` читается напрямую из
`SCL_NAKL.L_CP2_PLAT` — того же поля, которое detail endpoint возвращает как
`document.additionalInfo`. Java не вычисляет его и не объединяет с
`sourceInfo`, `comment` или другими реквизитами. Для `PAYMENT` в
`additionalInfo` напрямую возвращается экранное поле «Примечание» из
`SCL_PLAT.DOCUMN_POR`; detail endpoint уже возвращает его как `document.note`.
Пустое значение любого типа документа возвращается как `null`.

## 2. Детали документа

```http
GET /admin/folio/customer-documents/{documentType}/{documentId}?partnerShortName=...
```

`documentType`:

- `ACCOUNT` — счёт, кириллический `SCL_NAKL.TYPE_DOC='С'`;
- `EXPENSE` — расходная накладная, кириллический `SCL_NAKL.TYPE_DOC='Р'`;
- `PAYMENT` — платёж из `SCL_PLAT`.

Нужно использовать `documentType` и `documentId` именно из списка. Java повторно проверит принадлежность документа клиенту. Чужой или отсутствующий документ вернёт `404` без раскрытия его данных.

### Счёт или расходная накладная

Ответ содержит:

- полную доступную шапку `document`;
- `documentRequisites` из `SCL_ADDN`;
- `items` из `SCL_MOVE`;
- `linkedPayments` — связанные разнесения платежей через `SCL_PMOV`;
- `repeatOrder` — подготовленные позиции для повторного заказа.

Фрагмент:

```json
{
  "ok": true,
  "partner": {
    "shortName": "БОНД АНН",
    "name": "Бондаренко Анна"
  },
  "document": {
    "documentType": "EXPENSE",
    "documentId": 751193,
    "documentNumber": "64471",
    "documentNumberSuffix": "/0626",
    "documentDate": "2026-06-11T13:34:13.103",
    "totalAmount": 9284.34,
    "warehouseId": 7,
    "items": [
      {
        "lineId": 8980001,
        "lineNumber": 1,
        "sku": "KR-16234",
        "name": "Текущее название товара",
        "warehouseId": 7,
        "requestedQuantity": 2,
        "quantity": 2,
        "price": 130.00,
        "amount": 260.00,
        "repeatable": true
      }
    ],
    "linkedPayments": [],
    "allocations": [],
    "repeatOrder": {
      "allowed": true,
      "reason": null,
      "items": [
        {
          "sku": "KR-16234",
          "name": "Текущее название товара",
          "quantity": 2,
          "historicalPrice": 130.00,
          "currencyCode": null
        }
      ]
    },
    "source": "ACTIVE_LEDGER"
  },
  "warnings": []
}
```

### Платёж

У платежа `items` и `linkedPayments` пустые. Поле `allocations` показывает товарные/документные распределения `SCL_PMOV`, а `paymentRequisites` — доступные реквизиты `SCL_ADDP`.

`paymentDirectionRaw` возвращает исходный `SCL_PLAT.TYPE_POR`. Фронт не должен самостоятельно называть его «приходом» или «расходом», пока отображение не согласовано с бизнес-правилом ФОЛИО.

Платёж нельзя повторно загрузить в корзину:

```json
"repeatOrder": {
  "allowed": false,
  "reason": "PAYMENT_NOT_REPEATABLE",
  "items": []
}
```

## 3. Загрузка в корзину или черновик Woo

Java не создаёт корзину и черновик: это состояние WooCommerce. Фронт/WordPress использует только `document.repeatOrder.items`.

Алгоритм:

1. разрешить действие только при `repeatOrder.allowed=true`;
2. по каждому `sku` найти актуальный товар Woo;
3. проверить, что товар разрешён к продаже;
4. добавить требуемое `quantity` в корзину либо строки Woo draft order;
5. если один SKU отсутствует или недоступен, показать его отдельно и продолжить с доступными позициями после подтверждения клиента;
6. цену брать из текущего Woo, а `historicalPrice` показывать только как справочную цену старого документа;
7. никогда не отправлять товарные изменения обратно в ФОЛИО из этого сценария.

Причины запрета повторения:

| `reason` | Значение |
|---|---|
| `PAYMENT_NOT_REPEATABLE` | платёж не содержит заказа |
| `RETURN_DOCUMENT` | возвратный документ нельзя повторить как обычный заказ |
| `NO_REPEATABLE_ITEMS` | нет строк с положительным количеством и корректным SKU |
| `DOCUMENT_TYPE_NOT_REPEATABLE` | тип документа не поддерживает повторение |

## 4. Ограничения первой версии

- Читаются только активные таблицы ФОЛИО. Архивные `SCL_ARCN/SCL_ARCM/...` пока не включены.
- Название позиции берётся из текущей карточки `SCL_ARTC`; историческое название в строке движения не хранится и может отличаться.
- Историческая цена не должна перезаписывать цену Woo.
- Используется неблокирующее чтение `NOLOCK`, поэтому документ, который оператор меняет прямо сейчас, может дать кратковременно несогласованный ответ. Повторный запрос это исправит.
- Поля, отсутствующие у конкретного типа документа, возвращаются как `null` или пустой список — это нормально.

## 5. Ошибки

| HTTP | Ситуация |
|---:|---|
| 400 | неверная дата, период больше 366 дней, неизвестный тип, повреждённый cursor, неверный limit |
| 404 | клиент не найден либо документ не принадлежит указанному клиенту/не найден |
| 503 | ошибка чтения ФОЛИО |

Фронт должен показывать клиенту нейтральный текст и логировать `reqId` из ответа для поиска серверной ошибки.
