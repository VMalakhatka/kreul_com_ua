# Отчёт «Баланс с клиентом»: контракт и требования для frontend

## 1. Назначение

Экран строит клиентскую сверку по логике штатного **7-го отчёта ФОЛИО** и привычного Excel-шаблона `7Й чистый.xls`.

Frontend не рассчитывает долг самостоятельно. Он:

1. выбирает клиента и необязательные фильтры;
2. вызывает Java API;
3. показывает готовые `summary`, `rows` и классификационные признаки;
4. сохраняет порядок строк, полученный от backend.

Источник финансовой истины — ответ Java API. Нельзя повторно вычислять задолженность по документам WooCommerce или суммировать только видимые строки таблицы.

## 2. Подключение для Codex/frontend-чата

При реализации этого экрана подключить навык:

```text
$work-with-folio-mssql
```

Локальный источник навыка:

```text
.agents/skills/work-with-folio-mssql/SKILL.md
```

Навык нужен агенту для правильной трактовки ФОЛИО, предоплат и отсрочек. В браузерный bundle он не включается и runtime-зависимостью frontend не является.

Главный технический контракт — этот файл. Подробное обоснование backend-расчёта находится в `docs/api/FOLIO_CUSTOMER_BALANCE_API.md`.

Инструкция для менеджеров по заполнению документов ФОЛИО находится в `docs/business/FOLIO_BALANCE_MARKERS_FOR_MANAGERS.md`. В интерфейсе отчёта желательно дать на неё ссылку рядом с пояснением `РЕЛ/ПРД`.

## 3. Безопасная схема вызова

Endpoint административный:

```http
GET /admin/folio/customer-balance
```

Рекомендуемый маршрут:

```text
браузер → авторизованный WordPress/backend proxy → Java API → ФОЛИО
```

Не хранить Java API key, Basic password или другой серверный секрет в JavaScript. Не обращаться из публичной клиентской страницы прямо к открытому `/admin/**`, если для него нет подтверждённой пользовательской авторизации.

Base URL должен приходить из конфигурации окружения, а не быть захардкожен в компоненте.

## 4. Параметры запроса

| Параметр | Обязательный | Тип | Правило |
|---|---:|---|---|
| `partnerShortName` | да | `string` | точное краткое имя клиента `_PARTNER.N_USER`, максимум 8 символов |
| `dateFrom` | нет | `yyyy-MM-dd` | начальная дата; если не передана, отчёт строится за весь доступный активный период |
| `warehouseIds` | нет | повторяемый `integer` | без параметра используются все склады — это основной режим 7-го отчёта |
| `includeServicePayments` | нет | `boolean` | по умолчанию `true`; соответствует служебному флагу `I_DOLG_DOC.Show_zv` |

Параметра `dateTo` нет. Конечная дата всегда равна текущей дате **Java-сервера**. Для заголовка отчёта frontend должен использовать `filters.dateTo`/`filters.asOfDate` из ответа, а не часы браузера.

### Выбор клиента

Для выбора клиента использовать partners API и передавать именно выбранное `shortName`, а не полное `name` и не строку, вручную введённую пользователем:

```http
GET /admin/folio/partners?q=...
```

В форме показывать пользователю оба значения:

```text
БОНД АНН — Бондаренко Ганна Ігорівна ФОП
```

В customer-balance передавать только:

```text
partnerShortName=БОНД АНН
```

### Рекомендуемый запрос

Каноническая сверка за период по всем складам:

```http
GET /admin/folio/customer-balance?partnerShortName=БОНД%20АНН&dateFrom=2026-01-01
```

За весь доступный период:

```http
GET /admin/folio/customer-balance?partnerShortName=БОНД%20АНН
```

Выбранные склады:

```http
GET /admin/folio/customer-balance?partnerShortName=БОНД%20АНН&dateFrom=2026-01-01&warehouseIds=7&warehouseIds=1
```

`warehouseIds` добавляется в URL несколько раз. Не отправлять `warehouseIds=` с пустым значением.

Для обычной клиентской сверки оставить режим **«Все склады»** и вообще не передавать `warehouseIds`. При выбранном наборе складов документ включается только тогда, когда все его строки относятся к этому набору.

### Формирование URL в JavaScript

```js
function buildCustomerBalanceQuery(filters) {
  const params = new URLSearchParams();

  params.set('partnerShortName', filters.partnerShortName);

  if (filters.dateFrom) {
    params.set('dateFrom', filters.dateFrom); // yyyy-MM-dd
  }

  for (const warehouseId of filters.warehouseIds ?? []) {
    params.append('warehouseIds', String(warehouseId));
  }

  if (typeof filters.includeServicePayments === 'boolean') {
    params.set(
      'includeServicePayments',
      String(filters.includeServicePayments),
    );
  }

  return params.toString();
}
```

Не собирать URL конкатенацией: краткое имя клиента может содержать пробелы и кириллицу.

## 5. Типы ответа для TypeScript

```ts
type IsoDate = string; // yyyy-MM-dd
type Money = number;

interface FolioCustomerBalanceResponse {
  ok: boolean;
  partner: {
    shortName: string;
    name: string;
  };
  filters: {
    dateFrom: IsoDate;
    dateTo: IsoDate;
    asOfDate: IsoDate;
    warehouseIds: number[];
    warehouseMode:
      | 'ALL_WAREHOUSES'
      | 'ALL_DOCUMENT_LINES_IN_SELECTED_WAREHOUSES'
      | string;
    includeServicePayments: boolean;
  };
  summary: {
    openingBalance: Money;
    expenseTotal: Money;
    receiptTotal: Money;
    bankPaymentTotal: Money;
    cashPaymentTotal: Money;
    commonDebt: Money;
    deferredAmount: Money;
    overdueDeferredAmount: Money;
    prepaymentAmount: Money;
    payableNow: Money;
  };
  rows: FolioCustomerBalanceRow[];
  warnings: FolioCustomerBalanceWarning[];
}

interface FolioCustomerBalanceRow {
  sequence: number;
  controlDate: IsoDate | null;
  documentType: string | null;
  documentNumber: string | null;
  documentDate: IsoDate | null;
  basis: string | null;
  balanceBefore: Money;
  expenseAmount: Money;
  receiptAmount: Money;
  bankPayment: Money;
  cashPayment: Money;
  balanceAfter: Money;
  note: string | null;
  invoiceDate: IsoDate | null;

  openingBalanceRow: boolean;
  deferred: boolean;
  overdueDeferred: boolean;
  prepayment: boolean;
  deferredAmount: Money;
  overdueDeferredAmount: Money;
  prepaymentAmount: Money;

  documentId: number | null;
  warehouseId: number | null;
  warehouseName: string | null;
  folioDocumentKind: string | null;
}

interface FolioCustomerBalanceWarning {
  code: string;
  message: string;
  details: Record<string, unknown>;
}
```

Даты текущего API должны приходить ISO-строками `yyyy-MM-dd`. Если deployment возвращает массив `[2026, 8, 13]`, не закреплять это как новый frontend-контракт: это признак старой или несогласованной конфигурации сериализации backend.

## 6. Пример ответа

Сокращённый, но арифметически согласованный пример:

```json
{
  "ok": true,
  "partner": {
    "shortName": "КЛИЕНТ",
    "name": "Тестовый клиент"
  },
  "filters": {
    "dateFrom": "2026-01-01",
    "dateTo": "2026-08-13",
    "asOfDate": "2026-08-13",
    "warehouseIds": [],
    "warehouseMode": "ALL_WAREHOUSES",
    "includeServicePayments": true
  },
  "summary": {
    "openingBalance": 100.00,
    "expenseTotal": 1400.00,
    "receiptTotal": 0.00,
    "bankPaymentTotal": 200.00,
    "cashPaymentTotal": 0.00,
    "commonDebt": 1500.00,
    "deferredAmount": 1000.00,
    "overdueDeferredAmount": 400.00,
    "prepaymentAmount": 200.00,
    "payableNow": 500.00
  },
  "rows": [
    {
      "sequence": 0,
      "controlDate": null,
      "documentType": null,
      "documentNumber": "НА НАЧАЛО",
      "documentDate": null,
      "basis": "Долг на начало",
      "balanceBefore": 100.00,
      "expenseAmount": 0.00,
      "receiptAmount": 0.00,
      "bankPayment": 0.00,
      "cashPayment": 0.00,
      "balanceAfter": 100.00,
      "note": null,
      "invoiceDate": null,
      "openingBalanceRow": true,
      "deferred": false,
      "overdueDeferred": false,
      "prepayment": false,
      "deferredAmount": 0.00,
      "overdueDeferredAmount": 0.00,
      "prepaymentAmount": 0.00,
      "documentId": null,
      "warehouseId": null,
      "warehouseName": null,
      "folioDocumentKind": null
    },
    {
      "sequence": 1,
      "controlDate": "2026-09-01",
      "documentType": "Р",
      "documentNumber": "1001",
      "documentDate": "2026-08-01",
      "basis": "РЕЛ Реализация",
      "balanceBefore": 100.00,
      "expenseAmount": 1000.00,
      "receiptAmount": 0.00,
      "bankPayment": 0.00,
      "cashPayment": 0.00,
      "balanceAfter": 1100.00,
      "note": null,
      "invoiceDate": "2026-08-01",
      "openingBalanceRow": false,
      "deferred": true,
      "overdueDeferred": false,
      "prepayment": false,
      "deferredAmount": 1000.00,
      "overdueDeferredAmount": 0.00,
      "prepaymentAmount": 0.00,
      "documentId": 700001,
      "warehouseId": 7,
      "warehouseName": "Киев ОПТ",
      "folioDocumentKind": "РЕАЛИЗАЦИЯ"
    },
    {
      "sequence": 2,
      "controlDate": null,
      "documentType": "ПБ",
      "documentNumber": "501",
      "documentDate": "2026-08-02",
      "basis": null,
      "balanceBefore": 1100.00,
      "expenseAmount": 0.00,
      "receiptAmount": 0.00,
      "bankPayment": 200.00,
      "cashPayment": 0.00,
      "balanceAfter": 900.00,
      "note": "ПРД Банковская предоплата",
      "invoiceDate": null,
      "openingBalanceRow": false,
      "deferred": false,
      "overdueDeferred": false,
      "prepayment": true,
      "deferredAmount": 0.00,
      "overdueDeferredAmount": 0.00,
      "prepaymentAmount": 200.00,
      "documentId": 800001,
      "warehouseId": 7,
      "warehouseName": "Киев ОПТ",
      "folioDocumentKind": null
    },
    {
      "sequence": 3,
      "controlDate": "2026-08-10",
      "documentType": "Р",
      "documentNumber": "1002",
      "documentDate": "2026-08-03",
      "basis": "РЕЛ Реализация",
      "balanceBefore": 900.00,
      "expenseAmount": 400.00,
      "receiptAmount": 0.00,
      "bankPayment": 0.00,
      "cashPayment": 0.00,
      "balanceAfter": 1300.00,
      "note": null,
      "invoiceDate": "2026-08-03",
      "openingBalanceRow": false,
      "deferred": false,
      "overdueDeferred": true,
      "prepayment": false,
      "deferredAmount": 0.00,
      "overdueDeferredAmount": 400.00,
      "prepaymentAmount": 0.00,
      "documentId": 700002,
      "warehouseId": 7,
      "warehouseName": "Киев ОПТ",
      "folioDocumentKind": "РЕАЛИЗАЦИЯ"
    }
  ],
  "warnings": [
    {
      "code": "FOLIO_NOLOCK_READ",
      "message": "I_DOLG_DOC uses NOLOCK; concurrent Folio edits can make one response internally non-snapshot",
      "details": {}
    },
    {
      "code": "ACTIVE_LEDGER_ONLY",
      "message": "The standard procedure does not include archived Folio documents",
      "details": {}
    },
    {
      "code": "LEGACY_DATE_TO_MIDNIGHT",
      "message": "I_DOLG_DOC treats dateTo as an inclusive midnight boundary",
      "details": {
        "dateTo": "2026-08-13"
      }
    }
  ]
}
```

## 7. Значение итогов

| Поле | Надпись в интерфейсе | Значение |
|---|---|---|
| `openingBalance` | Долг на начало | входящее сальдо перед `dateFrom` |
| `expenseTotal` | Расход по накладным | сумма расходных документов |
| `receiptTotal` | Приход по накладным | сумма возвратов/приходных документов |
| `bankPaymentTotal` | Оплата, банк | итог банковских платежей |
| `cashPaymentTotal` | Оплата, касса | итог кассовых платежей |
| `commonDebt` | Общий долг | общее сальдо с учётом всех документов и платежей |
| `deferredAmount` | На отсрочке/реализации | накладные с маркером `РЕЛ`, срок оплаты которых ещё не наступил |
| `overdueDeferredAmount` | Просрочено по отсрочке/реализации | документы с `РЕЛ`, срок которых уже наступил |
| `prepaymentAmount` | Предоплата | банк **плюс** касса по платежам с примечанием `ПРД...` |
| `payableNow` | К оплате | сумма, которую клиент должен оплатить сейчас |

Положительный `payableNow` — клиент должен оплатить эту сумму. Нулевое или отрицательное значение означает, что текущей суммы к оплате нет; отрицательное значение показывать как кредит/переплату, не превращать в `0`.

### Формулы уже выполнены backend

```text
accountingBalance = openingBalance
                    + expenseTotal
                    - receiptTotal
                    - bankPaymentTotal
                    - cashPaymentTotal

commonDebt = accountingBalance + prepaymentAmount

payableNow = commonDebt - deferredAmount
```

Frontend не должен применять эти формулы повторно. Они приведены только для объяснения пользователю и тестирования отображения.

Почему предоплата прибавляется к техническому `accountingBalance`: входящий платёж уже уменьшил штатный баланс ФОЛИО, но платёж с `ПРД` предназначен под будущий товар и не должен погашать старый долг. Поэтому backend возвращает `commonDebt` уже без влияния предоплаты, а саму предоплату показывает отдельно.

`overdueDeferredAmount` уже входит в `commonDebt` и `payableNow`. Его нельзя ещё раз прибавлять к сумме «к оплате».

## 8. Предоплата `ПРД`

Предоплатой является **любой входящий банковский или кассовый платёж клиента**, примечание которого начинается с `ПРД`:

```text
ПРДПредоплата
ПРД Банковская предоплата
```

Речь идёт о поле **Примечание входящего банковского или кассового платежа** (`SCL_PLAT.DOCUMN_POR`). Маркер не записывается в счёт, расходную накладную или поле «Основание».

Она может находиться как в:

- `bankPayment`;
- `cashPayment`.

Правильное значение строки уже приходит в `row.prepaymentAmount`, а признак — в `row.prepayment=true`.

Нельзя:

- учитывать только кассу;
- определять предоплату по `documentType`;
- повторно искать `ПРД` и самостоятельно пересчитывать сумму на frontend;
- считать предоплату оплатой текущего долга.

В старом Excel была формула только по кассе. API намеренно исправляет эту ошибку и учитывает `bankPayment + cashPayment`.

После поставки предоплаченного товара оператор должен убрать `ПРД` из примечания соответствующего платежа. Иначе backend продолжит показывать эту сумму как отдельную предоплату, не погашающую текущую задолженность.

## 9. Отсрочка и просрочка

Маркер `РЕЛ` оператор ставит в поле **Основание расходной накладной** (`SCL_NAKL.OSNOVANIE`) и обязательно заполняет её **Контр.срок** (`SCL_NAKL.CONTRLDATE`). Это не поле счёта и не поле платёжного документа.

Использовать готовые признаки backend:

| Состояние | Признак строки | Как показывать |
|---|---|---|
| `basis` начинается с `РЕЛ` и срок ещё не наступил | `deferred=true` | светло-жёлтая строка; явно показать `controlDate` |
| срок наступил, `РЕЛ` остался | `overdueDeferred=true` | красное предупреждение; `basis` выделить красным |
| обычный документ | оба признака `false` | стандартная строка |

Не сравнивать даты повторно в браузере: часовой пояс пользователя может отличаться от даты Java-сервера. Для классификации используются `deferred` и `overdueDeferred`; `controlDate` служит для показа.

Одна будущая `controlDate` без маркера `РЕЛ` в `basis` не является отсрочкой. В обычных накладных контрольная дата не является контролируемым бизнес-признаком. Backend обязан вернуть для такой строки `deferred=false`; frontend не должен исправлять это своей формулой, но должен считать ответ с `deferred=true` дефектом API.

Просроченная отсрочка может быть уже частично оплачена. Поэтому `overdueDeferredAmount` — контрольная сумма документов с просроченным маркером, а не отдельная сумма, которую нужно добавить к оплате.

После полной оплаты товара с реализации/отсрочки оператор должен убрать `РЕЛ` из поля **Основание** расходной накладной. Иначе документ продолжит отображаться в списке просроченных.

## 10. Внешний вид — сохранить язык Excel-отчёта

### Заголовок

Показать:

```text
Сверка с покупателем
<полное имя клиента> (<краткое имя>)
Период: <dateFrom> — <dateTo>
Склады: Все склады | список выбранных складов
Состояние на: <asOfDate>
```

Если `filters.dateFrom` равна `1753-01-01`, вместо технической даты писать **«за весь период»**.

### Итоговый блок

Повторить смысловую цветовую схему `7Й чистый.xls`:

| Блок | Поля | Рекомендуемый цвет |
|---|---|---|
| «Итоговые суммы» | расход, приход, банк, касса, общий долг | светло-голубой `#CCFFFF` |
| «На отсрочке/реализации» | `deferredAmount` | светло-жёлтый `#FFFF99` |
| «Просрочено по отсрочке/реализации» | `overdueDeferredAmount` | красный/светло-красный; обеспечить контраст текста |
| «Предоплата» | `prepaymentAmount` | зелёный, близкий к `#339966` |
| «К оплате» | `payableNow` | ярко-жёлтый `#FFFF00`; это главный показатель |

На мобильном экране карточки складываются вертикально. На desktop основной блок можно показать сеткой, но порядок смыслов сохранить.

`payableNow` сделать самым заметным числом. `commonDebt` нельзя подписывать как «к оплате»: в нём ещё находятся будущая отсрочка и влияние предоплаты.

### Таблица — ровно 14 основных колонок

Порядок нельзя менять:

| № | Заголовок Excel | JSON | Выравнивание |
|---:|---|---|---|
| 1 | Контр.срок | `controlDate` | центр |
| 2 | NN | `sequence` | центр |
| 3 | Д | `documentType` | центр |
| 4 | N документа | `documentNumber` | слева |
| 5 | Дата | `documentDate` | центр |
| 6 | Основание | `basis` | слева |
| 7 | Долг,нач. | `balanceBefore` | справа |
| 8 | Сум.расхода по накл. | `expenseAmount` | справа |
| 9 | Сум.прихода по накл. | `receiptAmount` | справа |
| 10 | Опл.банк | `bankPayment` | справа |
| 11 | Опл.касса | `cashPayment` | справа |
| 12 | Долг, конец | `balanceAfter` | справа |
| 13 | Примечание | `note` | слева |
| 14 | Дата счета | `invoiceDate` | центр |

Технические поля `documentId`, `warehouseId`, `warehouseName`, `folioDocumentKind` не добавлять в основные 14 колонок. Их показывать в раскрывающейся строке, tooltip или боковой панели «Детали документа».

### Правила таблицы

- Не сортировать `rows` на frontend. `balanceBefore` и `balanceAfter` рассчитаны именно для серверного порядка.
- Строку `openingBalanceRow=true` закрепить первой, сделать полужирной и нейтрально-серой.
- `overdueDeferred=true` имеет наивысший приоритет красного выделения.
- `deferred=true` — светло-жёлтый фон строки.
- `prepayment=true` — зелёный фон/маркер; в `note` визуально выделить начало `ПРД`.
- Заголовок таблицы сделать sticky.
- На узких экранах использовать горизонтальную прокрутку, а не удалять финансовые колонки.
- Текстовые колонки допускают перенос; денежные числа не переносить.
- Для длинного отчёта использовать виртуализацию строк или постраничный UI только визуально. Нельзя менять порядок и нельзя пересчитывать баланс отдельно по странице.

### Форматирование

- Даты: `dd.MM.yyyy`.
- Деньги: два знака после запятой и разделитель тысяч.
- Основная единица: грн.
- Форматировать через `Intl.NumberFormat`, не через ручную замену точки на запятую.
- Отрицательные значения сохранять и выделять; не применять `Math.abs()` и не заменять их нулём.
- В итоговых карточках показывать `0,00`; в таблице нулевые движения допустимо показывать пустой ячейкой для сходства с Excel.

Пример:

```js
const moneyFormatter = new Intl.NumberFormat('uk-UA', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

const dateFormatter = new Intl.DateTimeFormat('uk-UA', {
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
  timeZone: 'UTC',
});
```

ISO-даты без времени безопаснее форматировать как компоненты строки, чтобы локальный часовой пояс браузера не сдвинул календарный день.

## 11. Фильтры и состояния экрана

Минимальная форма:

1. **Клиент** — обязательный autocomplete.
2. **Начальная дата** — необязательная; рядом действие «За весь период».
3. **Склады** — по умолчанию «Все склады»; выбор конкретных складов спрятать в дополнительные фильтры.
4. **Служебные платежи** — по умолчанию включены; также дополнительный фильтр.
5. Кнопка **«Сформировать отчёт»**.

Состояния:

- до выбора клиента — не выполнять запрос;
- загрузка — skeleton итогов и таблицы, предыдущий отчёт не смешивать с новым;
- `rows` содержит только начальную строку — показать корректный нулевой/начальный отчёт, а не ошибку;
- ошибка — сохранить фильтры и дать повторить запрос;
- новый запрос должен отменять предыдущий через `AbortController`.

## 12. Предупреждения API

Backend обычно возвращает три технических предупреждения:

| Код | Смысл для интерфейса |
|---|---|
| `FOLIO_NOLOCK_READ` | при параллельном редактировании ФОЛИО данные одного ответа теоретически могут быть не полностью моментальным снимком |
| `ACTIVE_LEDGER_ONLY` | архивные документы автоматически не включены |
| `LEGACY_DATE_TO_MIDNIGHT` | штатная процедура сравнивает конечный день с полуночью |

Не показывать клиенту сырой английский `message`. В административном интерфейсе можно вывести компактный раскрываемый блок «Особенности отчёта» с локализованными текстами.

Если отчёт отправляется клиенту, достаточно примечания:

```text
Отчёт сформирован по активным документам ФОЛИО на <asOfDate>.
```

## 13. Ошибки HTTP

| HTTP | Причина | Поведение frontend |
|---:|---|---|
| `400` | нет `partnerShortName`, имя длиннее 8 символов, неверная/будущая `dateFrom`, неправильный склад | показать сообщение рядом с фильтрами |
| `401/403` | нет доступа | не повторять бесконечно; предложить повторную авторизацию |
| `404` | клиент с таким кратким именем не найден | очистить выбранный объект клиента и предложить выбрать заново |
| `503` | ФОЛИО/MS SQL временно недоступна | сохранить фильтры и показать кнопку «Повторить» |
| `500` | непредвиденная ошибка | показать общий текст и `reqId` для поддержки |

В ошибке backend обычно возвращает:

```json
{
  "status": 404,
  "title": "Folio partner not found",
  "reqId": "...",
  "message": "..."
}
```

Для поддержки сохранять и показывать `reqId`, но не выводить пользователю SQL/stack trace.

## 14. Экспорт и печать

Если frontend добавляет экспорт:

- печать — A4 landscape;
- итоговый цветной блок располагается перед таблицей;
- 14 колонок сохраняются в том же порядке;
- заголовок таблицы повторяется на каждой печатной странице;
- строка «НА НАЧАЛО» остаётся первой;
- серверный порядок строк не меняется;
- технические поля не входят в клиентскую печатную форму;
- `payableNow`, предоплата, отсрочка и просрочка должны совпадать с `summary` без пересчёта в браузере.

## 15. Известная проблема опубликованного Swagger

На дату 2026-08-13 опубликованный `/v3/api-docs` правильно описывает endpoint и параметры, но для вложенной схемы с общим именем `Summary` может показывать поля другого API:

```text
requested, ready, noop, blocked, applied
```

Это конфликт имён вложенных DTO в генераторе OpenAPI. Для customer-balance эти поля **неверны**. Фактический `summary` описан в разделе 5 этого файла и содержит:

```text
openingBalance, expenseTotal, receiptTotal,
bankPaymentTotal, cashPaymentTotal, commonDebt,
deferredAmount, overdueDeferredAmount,
prepaymentAmount, payableNow
```

До исправления схемы backend не генерировать тип customer-balance автоматически из ошибочного `components.schemas.Summary`.

## 16. Критерии приёмки frontend

1. Клиент выбирается через partners API, в запрос уходит точный `shortName`.
2. Без `dateFrom` отчёт успешно строится за весь период.
3. Конечная дата берётся из ответа и всегда показывается пользователю.
4. По умолчанию запрос идёт по всем складам.
5. Итоговый блок содержит все десять значений `summary`.
6. «К оплате» использует только `summary.payableNow`.
7. Банковская строка с `prepayment=true` подсвечивается так же, как кассовая предоплата.
8. Предоплата не вычитается повторно и не считается оплатой текущего долга.
9. Просроченная отсрочка не прибавляется повторно к `payableNow`.
10. Будущая контрольная дата без `basis`, начинающегося с `РЕЛ`, не попадает в отсрочку.
11. Таблица содержит 14 основных колонок в старом порядке.
12. Строки не сортируются и не пересчитываются на frontend.
13. Даты показываются без сдвига часового пояса, деньги — с двумя знаками.
14. Все отрицательные суммы сохраняют знак.
15. Технические warnings не выводятся клиенту сырым английским текстом.
16. Ошибка показывает безопасное сообщение и `reqId`.
