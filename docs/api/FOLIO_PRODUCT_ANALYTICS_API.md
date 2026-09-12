# Product analytics schema v6

Обновлено: 2026-09-11.

В v6 исправлена семантика свободного остатка: `availableQuantity=REZ_KOLCH`,
`reservedQuantity=KON_KOLCH-REZ_KOLCH`. Нужны заново сформированные снимки
версии 6; query отвергает старые поколения, включая v5. Точный lifecycle:
[FOLIO_PRODUCT_SNAPSHOT_API.md](FOLIO_PRODUCT_SNAPSHOT_API.md#исправление-свободного-остатка-schema-6).
Формат availability из v5 сохранён.

Добавленное в v5: физическое наличие по дням, применимость MIN_TVRZAP и группы
складов. Точный контракт и инструкция фронту:
[FOLIO_PRODUCT_AVAILABILITY_FRONTEND_V5.md](FOLIO_PRODUCT_AVAILABILITY_FRONTEND_V5.md).
Остальные экономические формулы, включая `coverageDays`, не менялись.

API строит отчёты только по активным product snapshot в MariaDB. Запросы
`capabilities` и `query` не обращаются к ФОЛІО/MS SQL и ничего в ФОЛІО не
изменяют.

## Подготовка данных

1. Применить Flyway migrations
   `V11__folio_product_analytics_schema_v3.sql` и
   `V12__folio_product_analytics_schema_v4.sql` и
   `V13__folio_product_availability_history.sql`.
2. После деплоя заново выполнить
   `POST /admin/folio/accounting-prices/snapshot/refresh` для каждого склада,
   который должен участвовать в аналитике.
3. Дождаться `status=ACTIVE`, `phase=COMPLETED` и
   `analyticsSchemaVersion=6`.

V12 использует `IF NOT EXISTS`, потому что MariaDB DDL не откатывается вместе
с Flyway-транзакцией во всех режимах. После прерванного старта migration может
успеть изменить current-таблицу, но не stage-таблицу. Повторный деплой должен
безопасно завершить недостающую часть. До успешного старта Java и регистрации
V12 в `flyway_schema_history` product snapshot не запускать.
Для v5 также требуется успешная V13; миграция сама не заполняет историю.

Активный snapshot старой схемы продолжает обслуживать старые экраны, но новый
`query` вернёт `ANALYTICS_SCHEMA_TOO_OLD`, пока выбранный склад не обновлён.
Ошибка нового refresh не удаляет прежнее активное поколение.

## Capabilities

```http
POST /admin/folio/product-analytics/capabilities
Content-Type: application/json
```

```json
{
  "sourceDatabase": "Paint_Ua",
  "warehouseIds": [1, 5, 7]
}
```

Ответ сообщает поколения выбранных складов, фактически поддержанные фильтры и
их справочники. Даты всегда являются ISO-строками `yyyy-MM-dd`, время —
`yyyy-MM-dd'T'HH:mm:ss.SSS`.

`purchasePolicy` отдельно сообщает готовность сетевой политики склада 7
«Киев ОПТ», его generation и порог неограниченного максимума `9999`.
`transit` сообщает готовность каждого настроенного транспортного источника,
generation, подтверждённые типы организаций поставщика `Т`/`I` и способ расчёта.
Склад 9 — только совместимый default при отсутствии `calculation.transit`.
Контракт выбора нескольких складов, revision и handoff:
[FOLIO_TRANSIT_WAREHOUSES_FRONTEND.md](FOLIO_TRANSIT_WAREHOUSES_FRONTEND.md).

Поддержаны:

- поиск по SKU/названию/основному GTIN и явный список SKU/GTIN;
- общая группа и уровни групп 1–6;
- отдел, тип товара, единица измерения;
- текущий поставщик карточки и состояние его назначения;
- вид операции, класс движения, режим спроса, тип документа;
- направление движения, условия оплаты, сегмент клиента;
- контрагент документа и тип организации;
- ABC и несколько складов.

Основной GTIN берётся из `SCL_ARTC.DOP3_ARTIC`. Дополнительные штрихкоды
`SCL_CODE` в schema v5 не входят.

Пока возвращаются `supported=false`, `reason=SOURCE_NOT_CONFIRMED`:

- бренд;
- менеджер продаж (`salesManagerCodes`), не поставщик;
- склад-источник и склад-получатель перемещения;
- SCM lead time и открытые заказы;
- XYZ.

Подневный stockout поддержан в v5 через `calculation.availability` — см.
[контракт наличия](FOLIO_PRODUCT_AVAILABILITY_FRONTEND_V5.md).

Frontend обязан скрывать или отключать неподдержанные поля. Он не должен
подменять их эвристикой.

## Query

```http
POST /admin/folio/product-analytics/query
Content-Type: application/json
```

```json
{
  "sourceDatabase": "Paint_Ua",
  "warehouseIds": [1, 5, 7],
  "period": {"from": "2025-09-01", "to": "2026-08-31"},
  "productFilters": {
    "search": "KR-84",
    "groups": {"mode": "EXCLUDE", "values": ["SERVICE"]},
    "currentSuppliers": {"mode": "INCLUDE", "values": ["KREUL"]},
    "supplierStates": {"mode": "INCLUDE", "values": ["CURRENT"]}
  },
  "movementFilters": {
    "movementClasses": {
      "mode": "EXCLUDE",
      "values": ["TRANSFER_IN", "TRANSFER_OUT"]
    },
    "demandModes": {"mode": "EXCLUDE", "values": ["ONE_OFF_ORDER"]}
  },
  "calculation": {
    "abcBasis": "GROSS_PROFIT",
    "includeReturns": true
  },
  "page": {"size": 50, "cursor": null},
  "sort": [{"field": "grossProfit", "direction": "DESC"}]
}
```

Для каждого справочного фильтра поддерживаются режимы:

- `ANY` — условие не применяется;
- `INCLUDE` — оставить указанные коды;
- `EXCLUDE` — исключить указанные коды.

Пустой `values` нормализуется в `ANY`. Значения надо брать из
`capabilities.dictionaries`, а не из видимого текста интерфейса.

### Product filters

`skus`, `groups`, `groupLevel1` … `groupLevel6`, `departments`,
`productTypes`, `units`, `currentSuppliers`, `supplierStates`, `barcodes`
используют объект `{mode, values}`. Поле `search` — строка до 200 символов.
Для `barcodes` значения валидируются непосредственно по активным снимкам и не
выгружаются огромным справочником в `capabilities.dictionaries`.

### Movement filters

`operationKinds`, `movementClasses`, `demandModes`, `documentTypes`,
`stockDirections`, `paymentTerms`, `customerSegments`, `counterparties`,
`organizationTypes` используют объект `{mode, values}`.

Фильтры движений меняют показатели периода, но не текущий остаток. Transfer не
классифицируется как продажа. `ONE_OFF_ORDER` сохраняется в фактических
финансовых показателях и отдельной one-off группе, но не входит в
`regularSoldUnits` и coverage.

### Calculation и сортировка

`abcBasis`: `REVENUE`, `GROSS_PROFIT` или `SOLD_UNITS`.

`serviceLevelPercent` и `demandHorizonDays` пока отклоняются кодом
`SOURCE_FIELD_NOT_CONFIRMED`: закупочный контур ещё не имеет подтверждённых
lead time и товара в пути.

Размер страницы: 1–500. Следующую страницу запрашивать с неизменными условиями
и `page.cursor=nextCursor` предыдущего ответа. В v5 курсор привязан к поколениям,
периоду, фильтрам, сортировке и определениям/ревизии групп. При смене любого
из этих входов — HTTP 409 `ANALYTICS_CURSOR_EXPIRED`: начать отчёт/экспорт заново,
не склеивать страницы разных снимков. Старые курсоры до v5 недействительны.

Сортировка: `sku`, `productName`, `physicalQuantity`, `inventoryValue`,
`soldUnits`, `salesRevenue`, `salesCogs`, `grossProfit`,
`averageInventoryValue`; направление `ASC` или `DESC`.

## Ответ query

- `context` — версия схемы, активное поколение и дата каждого склада, период;
- `appliedFilters` — нормализованные реально применённые условия;
- `totals` — итоги всей отфильтрованной совокупности, не только страницы;
- `rows` — агрегат SKU по выбранным складам;
- `rows[].warehouseBreakdown` — вклад каждого склада;
- `rows[].warehouseBreakdown[].orderPolicy` — интерпретация
  `MIN_TVRZAP/MAX_TVRZAP` конкретного склада;
- `rows[].networkOrderPolicy` — сетевое разрешение заказа по карточке склада 7
  «Киев ОПТ»;
- `rows[].dimensions.primaryBarcode` — основной GTIN карточки;
- `rows[].inTransitStock` — общий подтверждённый транзит и `sources[]` по отдельным транспортным складам;
- `rows[].abcClass` — A/B/C по выбранной базе;
- `facets` — справочники и counts выбранных активных snapshot;
- `nextCursor` — следующая страница или `null`;
- `warnings` — ограничения интерпретации;
- `errors` — пустой массив успешного отчёта.

Суммируемые показатели складываются по складам. Маржа, оборачиваемость, GMROI
и coverage пересчитываются из общих сумм. Текущий поставщик карточки в
`dimensions.currentSuppliers` и контрагент движения — разные сущности.

`includeReturns=true` показывает `returnQuantity` и `returnRevenue`, однако
return COGS пока не подтверждён. Поэтому `grossProfit` нельзя называть чистой
прибылью после возвратов; ответ содержит `RETURN_COGS_NOT_AVAILABLE`.

`facets` сейчас описывает выбранные склады до применения условий отчёта. Это
явно отмечено warning `FACETS_SCOPE_SELECTED_WAREHOUSES`.

## Товар в пути

`inTransitStock` строится только по опубликованным snapshot выбранных транспортных
складов (по умолчанию `[9]`) и не обращается к живой ФОЛІО во время отчёта.
В calculationVersion=3 физический запас сети включает внутренние перемещения:
источник `availableForNetworkPlanningQuantity = physicalQuantity - reservedQuantity`
не ограничен происхождением, но требует известных, неотрицательных и согласованных полей.
Поставщицкий `supplierInTransitAvailableQuantity` — отдельная часть этого запаса,
а не дополнительное слагаемое. Полный контракт и обязательная инструкция фронту:
[транспорт v3](FOLIO_TRANSIT_WAREHOUSES_FRONTEND.md).

Общий сетевой показатель дополнительно требует согласованности снимков продаж и
транспорта. У нынешних независимых v5-снимков доказательства общего среза нет:
`networkPlanningReady=false`, общий `availableForNetworkPlanningQuantity=null`,
`networkSnapshotConsistency.status=NETWORK_SNAPSHOT_CONSISTENCY_UNCONFIRMED`.
Простой refresh или одинаковая дата не исключает двойной учёт перемещённой партии.
Детали количества/поколений доступны; закупку автоматически не рекомендовать.
При пересечении областей — TRANSIT_SCOPE_OVERLAP; явный [] отключает учёт и даёт 0.

Следующие **supplierOriginStatus** — только отдельная диагностика происхождения,
они больше не определяют пригодность физического остатка источника для сети:

- `CONFIRMED_SUPPLIER_ORIGIN` — весь положительный входящий поток поставщицкий;
  `supplierInTransitAvailableQuantity=availableQuantity` на источнике;
- `NO_IN_TRANSIT_STOCK` — физический остаток равен 0, доступно 0;
- `NEGATIVE_TRANSIT_STOCK` — отрицательный остаток является ошибкой данных и
  не участвует в планировании;
- `OPENING_BALANCE_UNATTRIBUTED` — часть текущего остатка существовала до
  горизонта snapshot, происхождение не доказано;
- `NO_CONFIRMED_INBOUND` — положительный остаток есть, но подтверждённого
  входящего движения внутри горизонта нет;
- `MIXED_ORIGIN` — присутствуют перемещения, коррекции или иной непоставщицкий
  положительный входящий поток;
- `SKU_NOT_PRESENT`, `SNAPSHOT_NOT_READY`, `ANALYTICS_SCHEMA_TOO_OLD` — значение
  использовать нельзя.

При неподтверждённом происхождении `supplierOriginConfirmed=false`, а
`availableForPlanningQuantity=null`: frontend не должен считать такой остаток
гарантированным поставщицким товаром в пути, но известный физический запас остаётся
в деталях. Старое availableForPlanningQuantity deprecated. `suppliers` содержит контрагентов подтверждённых
приходов, `receiptQuantityInHorizon` и последнюю дату. Это исторический объём
приходов внутри горизонта, а не распределение текущего остатка по поставщикам.
Текущий поставщик карточки остаётся
отдельным полем и не подменяет контрагента прихода.

## Политика MIN_TVRZAP / MAX_TVRZAP

Backend не отдаёт эти значения как необъяснённые числа. Для каждого
SKU × склад формируется `orderPolicy`:

| Условие | `replenishmentMode` | Правило |
|---|---|---|
| `MIN_TVRZAP = 0` | `DO_NOT_ORDER` | на этот склад товар не заказывать |
| `MIN_TVRZAP > 0` | `FORECAST_PLUS_MINIMUM_STOCK` | к прогнозу добавить весь `reserveAboveForecast = MIN_TVRZAP`, включая 1 и 0,001 |
| `MIN_TVRZAP` отсутствует/отрицателен | `UNKNOWN` | рекомендацию заказа не формировать |

Уточнение владельца 2026-09-12: MIN=1 не является специальным признаком.
MIN=0,001 разрешает заказ и добавляет ровно 0,001. При округлении вверх эта
прибавка на границе упаковки может увеличить заказ на целую упаковку; её нельзя
молча обнулять. `FORECAST_ONLY` больше не возвращается в новых ответах.
Политика вычисляется при запросе из исходных MIN/MAX: нужна новая Java и новый
preview, пересборка существующих schema 6 snapshots не нужна. Уже открытый preview
содержит старые политики и не обновляется автоматически.

Верхний предел:

- `MAX_TVRZAP >= 9999` → `maximumStockLimited=false`, верхний лимит не
  применяется;
- `0 <= MAX_TVRZAP < 9999` → `maximumStockLimited=true`, после заказа запас не
  должен превышать `maximumStockLimit=MAX_TVRZAP`;
- отсутствующее или отрицательное значение → лимит неизвестен и возвращается
  `validationState` с ошибкой данных;
- `MIN_TVRZAP > MAX_TVRZAP` при ограниченном максимуме возвращает
  `validationState=MINIMUM_EXCEEDS_MAXIMUM` и `orderAllowed=null`: формировать
  заказ по противоречивым ограничениям нельзя.

Склад 7 «Киев ОПТ» имеет дополнительный сетевой смысл:

- если его `MIN_TVRZAP=0`, строка SKU получает
  `networkOrderPolicy.status=BLOCKED_BY_KYIV_OPT` и
  `networkOrderPolicy.orderAllowed=false`; товар нельзя заказывать для всей
  сети, даже когда на другом складе локальная политика разрешает заказ;
- если его `MIN_TVRZAP>0` и ограничения согласованы, возвращается
  `status=ALLOWED`;
- если `MIN_TVRZAP>0`, но максимум не заполнен, возвращается
  `status=ALLOWED_WITH_UNKNOWN_MAXIMUM`: сетевой запрет отсутствует, однако
  количество нельзя считать без ручной проверки верхнего лимита;
- отрицательный максимум или `MIN_TVRZAP > MAX_TVRZAP` возвращает
  `status=DATA_ISSUE` и `orderAllowed=null`;
- если snapshot schema v5 склада 7 отсутствует, устарел или SKU в нём не найден,
  сетевое разрешение равно `null`. Frontend не должен трактовать `null` как
  разрешение.

Эти правила пока описывают ограничения и резерв. Финальное количество заказа
не рассчитывается до появления подтверждённых lead time, товара в пути,
открытых заказов поставщику, MOQ и кратности поставщика.

## Ошибки

Успешный отчёт имеет HTTP 200. Фильтр никогда не игнорируется молча.

| HTTP | code | Значение |
|---|---|---|
| 400 | `UNSUPPORTED_FILTER` | неизвестный фильтр/поле сортировки |
| 400 | `UNSUPPORTED_FILTER_VALUE` | неизвестный код, SKU, cursor или значение |
| 400 | `INVALID_PERIOD` | неверный период или выход за горизонт snapshot |
| 400 | `INVALID_FILTER_MODE` | режим не ANY/INCLUDE/EXCLUDE |
| 400 | `SOURCE_FIELD_NOT_CONFIRMED` | источник фильтра/расчёта ещё не подтверждён |
| 409 | `INCOMPATIBLE_GENERATIONS` | выбранные склады имеют разные версии схемы |
| 409 | `ANALYTICS_SCHEMA_TOO_OLD` | требуется refresh schema v5 |
| 400 | `INVALID_AVAILABILITY` | неверная группа, ревизия, режим или отбор наличия |
| 409 | `ANALYTICS_CURSOR_EXPIRED` | входы отчёта/поколения изменились; начать экспорт заново |
| 503 | `SNAPSHOT_NOT_READY` | нет активного snapshot хотя бы одного склада |

Отсутствие готового snapshot склада 7 не ломает экономический отчёт: строки
возвращаются с warning `NETWORK_ORDER_POLICY_NOT_READY`, но закупочная
рекомендация считается неготовой.
Аналогично отсутствие готового транспортного источника не ломает отчёт, но возвращает warning
`IN_TRANSIT_STOCK_NOT_READY` и не даёт использовать транзит в планировании.

Тело ошибки содержит `code`, `message` и при наличии `details`.

## Безопасность и производительность

- Browser вызывает WordPress proxy; прямой доступ к MariaDB/MSSQL не нужен.
- Оба endpoint выполняют только server-side SELECT к MariaDB.
- Один query выполняется в read-only repeatable-read транзакции, поэтому не
  смешивает публикацию поколений посередине ответа.
- Snapshot остаётся единственной тяжёлой read-only операцией к ФОЛІО.
