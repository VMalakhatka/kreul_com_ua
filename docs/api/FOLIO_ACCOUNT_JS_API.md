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
  "documentNumber": "WEB-2026-00001",
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
- Для создания использовать только `operationType: "СЧЕТ"`.
- В ФОЛИО `SCL_NAKL.TYPE_DOC` для счёта пишется как подтверждённая константа `C`; JS её не передаёт.
- В строках ФОЛИО `SCL_MOVE.TYPDOCM_PR` тоже пишется как `C`, а `SCL_MOVE.VID_DOC` — как подтверждённая константа `*РАЗОВАЯ`; JS их не передаёт.
- После `409 INSUFFICIENT_AVAILABLE_STOCK` не повторять тот же запрос без изменения количества/остатков.
