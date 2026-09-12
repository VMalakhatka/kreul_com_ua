# Задание фронту: аналитика товаров ФОЛІО, schema v4

Обновлено: 2026-08-31.

Это единая передача backend-контракта фронту после доработок product analytics.
Она заменяет устные договорённости и черновики schema v3. Фронт ФОЛІО/MS SQL не
изменяет и напрямую к ним не подключается.

## Какие skills подключить

Работать в таком порядке:

1. `$personal-skill-router` — определить границы между WordPress, Java API,
   ФОЛІО и экономической аналитикой.
2. `$lavka-woo` — реализовать WordPress/PHP proxy, экран, фильтры, пагинацию,
   локализацию и права доступа в `lavka-price-sync`.
3. `$folio-inventory-profit-planning` — проверить бизнес-смысл остатков,
   капитала, валовой прибыли, ABC, оборачиваемости, GMROI, покрытия,
   `MIN_TVRZAP/MAX_TVRZAP` и товара в пути.
4. `$work-with-folio-mssql` — использовать только как справочник подтверждённых
   источников ФОЛІО. Фронт не должен выполнять SQL и не должен придумывать
   эвристики для неподтверждённых полей.

`$build-java-docker-runtime` нужен только при совместной проверке деплоя Java;
для изменения WordPress-интерфейса он не требуется.

## Авторитетные файлы

- полный backend-контракт:
  `docs/api/FOLIO_PRODUCT_ANALYTICS_API.md`;
- это задание фронту:
  `docs/api/FOLIO_PRODUCT_ANALYTICS_FRONTEND_HANDOFF_V4.md`;
- подтверждённые источники и ограничения ФОЛІО:
  `docs/folio-experiments/32_product_analytics_v3_source_findings.md`;
- запросы read-only исследования:
  `docs/folio-experiments/30_product_analytics_v3_source_probe.sql` и
  `31_product_analytics_v3_fill_probe.sql`;
- точный Java HTTP-контракт:
  `src/main/java/org/example/proect/lavka/controller/FolioProductAnalyticsController.java`,
  `src/main/java/org/example/proect/lavka/dto/folio/FolioProductAnalyticsCapabilitiesResponse.java`,
  `FolioProductAnalyticsQueryRequest.java` и
  `FolioProductAnalyticsQueryResponse.java`;
- MariaDB migrations:
  `src/main/resources/db/wp/migration/V11__folio_product_analytics_schema_v3.sql`
  и `V12__folio_product_analytics_schema_v4.sql`.

Черновик WordPress
`docs/api/FOLIO_PRODUCT_ANALYTICS_SCENARIOS_BACKEND_TASK.md` в проекте `public`
остаётся историей исходного запроса. Если он расходится с schema v4, верен
backend-файл `FOLIO_PRODUCT_ANALYTICS_API.md`.

## Архитектурная граница

Правильная цепочка:

`browser -> WordPress nonce/capability proxy -> Java analytics API -> active MariaDB snapshot`

Для нового экрана сценариев WordPress не должен:

- обращаться из браузера напрямую к Java;
- обращаться к ФОЛІО/MS SQL;
- воспроизводить SQL analytics в PHP;
- считать заново финансовые коэффициенты;
- подменять неподтверждённое значение нулём;
- смешивать поставщика карточки, поставщика прихода и менеджера продаж.

## Зависимость от деплоя и снимков

1. Сначала задеплоить Java с Flyway migrations V11 и V12.
2. Последовательно перестроить product snapshot каждого используемого склада.
3. Обязательно перестроить склад 7 «Киев ОПТ» для сетевых ограничений заказа.
4. Обязательно перестроить склад 9 «Транспорт» для товара в пути.
5. Рабочий snapshot должен иметь `status=ACTIVE`, `phase=COMPLETED` и
   `analyticsSchemaVersion=4`.

Старый активный snapshot не удаляется при ошибке нового refresh, но новый
analytics query для schema v4 вернёт `ANALYTICS_SCHEMA_TOO_OLD`. Фронт должен
показать это как требование обновить снимок, а не как «товаров нет».

Если склад 7 не готов, отчёт может отображаться, но сетевое разрешение заказа
неизвестно. Если склад 9 не готов, отчёт может отображаться, но товар в пути
неизвестен. `null` никогда не означает ноль или разрешение.

## Endpoints

### Возможности и справочники

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

Перед построением формы всегда запрашивать `capabilities`. Поле показывать как
рабочий фильтр только при `filters.<name>.supported=true`. При `false` поле
скрыть или заблокировать и при необходимости вывести `reason`.

Справочные коды брать из `dictionaries`, а не из видимой подписи. Исключение —
GTIN: огромный список штрихкодов не выдаётся, пользователь вводит его строкой,
а backend проверяет по snapshot.

### Отчёт

```http
POST /admin/folio/product-analytics/query
Content-Type: application/json
```

Минимальный запрос:

```json
{
  "sourceDatabase": "Paint_Ua",
  "warehouseIds": [1, 5, 7],
  "period": {"from": "2025-09-01", "to": "2026-08-31"},
  "productFilters": {},
  "movementFilters": {},
  "calculation": {"abcBasis": "GROSS_PROFIT", "includeReturns": true},
  "page": {"size": 50, "cursor": null},
  "sort": [{"field": "grossProfit", "direction": "DESC"}]
}
```

Пагинация серверная. Для следующей страницы повторить неизменный запрос и
передать `page.cursor=nextCursor`. Не строить отчёт загрузкой всех SKU в PHP.

### Построение данных

- `POST /admin/folio/accounting-prices/snapshot/refresh` — построить read-only
  snapshot одного склада;
- `GET /admin/folio/accounting-prices/snapshot/status` — проверить текущую
  операцию snapshot.

Product snapshot и экран экономической статистики не являются перерасчётом
учётных цен. Они читают ФОЛІО и публикуют подготовленные данные в MariaDB.

## Что добавлено и изменено сегодня

### 1. Основной GTIN

- подтверждённый источник: `SCL_ARTC.DOP3_ARTIC`;
- в Woo это тот же идентификатор, который проходит как `global_unique_id` и
  сохраняется в `_wc_gtin_code`;
- ответ: `rows[].dimensions.primaryBarcode`;
- поиск `productFilters.search` ищет SKU, название и основной GTIN;
- точный отбор: `productFilters.barcodes` с `{mode, values}`;
- GTIN входит в analytics digest, но не делает SKU `DIRTY` для перерасчёта
  учётной цены.

Дополнительные коды из `SCL_CODE` пока не входят в контракт. Нельзя показывать
`primaryBarcode` как «все штрихкоды».

### 2. Товар в пути

Товар в пути вычисляется из активного snapshot склада 9 «Транспорт». Во время
отчёта живой запрос к ФОЛІО не выполняется.

Ответ: `rows[].inTransitStock`:

- `warehouseId`, `warehouseName`, `generationId`;
- `status`, `supplierOriginConfirmed`;
- `physicalQuantity`, `reservedQuantity`, `availableQuantity`;
- `availableForPlanningQuantity`;
- `openingQuantityAtHorizon`, `lastSupplierReceiptDate`;
- `suppliers[]` с поставщиком, историческим приходом внутри горизонта и датой.

Для закупочного планирования использовать количество только когда:

```text
status = CONFIRMED_SUPPLIER_ORIGIN
supplierOriginConfirmed = true
availableForPlanningQuantity != null
```

Статусы интерфейса:

| status | Что показать | Можно учитывать в заказе |
|---|---|---|
| `CONFIRMED_SUPPLIER_ORIGIN` | Подтверждённый товар в пути | Да, `availableForPlanningQuantity` |
| `NO_IN_TRANSIT_STOCK` | Товара в пути нет | Да, как 0 |
| `NEGATIVE_TRANSIT_STOCK` | Ошибка: отрицательный остаток транспорта | Нет |
| `OPENING_BALANCE_UNATTRIBUTED` | Происхождение старого остатка не доказано | Нет |
| `NO_CONFIRMED_INBOUND` | Есть остаток, но нет подтверждённого прихода | Нет |
| `MIXED_ORIGIN` | Смешанные приходы/перемещения/коррекции | Нет |
| `SKU_NOT_PRESENT` | SKU отсутствует на складе 9 | Не считать подтверждённым транзитом |
| `SNAPSHOT_NOT_READY` | Снимок склада 9 не готов | Нет |
| `ANALYTICS_SCHEMA_TOO_OLD` | Снимок склада 9 надо обновить | Нет |

`suppliers[].receiptQuantityInHorizon` — историческая сумма приходов за
горизонт, а не распределение текущего остатка между поставщиками.

### 3. Поставщик, продавец и менеджер

- `currentSuppliers` уже поддержан: это текущий поставщик карточки товара;
- поставщики в `inTransitStock.suppliers` — контрагенты подтверждённых
  приходов на транспорт;
- старое черновое имя `sellerCodes` удалено;
- новое имя фильтра `salesManagerCodes`, но оно пока
  `supported=false`, `reason=SOURCE_NOT_CONFIRMED`;
- бренд также `supported=false`; поставщика нельзя автоматически считать
  брендом.

Фронт должен удалить/не отправлять `sellerCodes`. `salesManagerCodes` пока не
показывать рабочим фильтром.

### 4. Группы, карточка товара и виды движений

Поддержаны фильтры товара:

- SKU, название, основной GTIN;
- общая группа и уровни групп 1–6;
- отдел, тип товара, единица измерения;
- текущий поставщик и состояние назначения поставщика;
- min/max stock, упаковка и минимальная партия доступны в измерениях/политике.

Поддержаны фильтры движений:

- вид операции;
- класс движения;
- режим спроса, включая разовый заказ;
- тип документа;
- направление;
- условие оплаты;
- сегмент клиента;
- контрагент документа;
- тип организации.

Фильтры движений меняют только показатели выбранного периода. Они не должны
менять текущий остаток. Перемещение не является продажей. `ONE_OFF_ORDER`
остаётся в фактической выручке/себестоимости, но не входит в регулярный спрос и
coverage.

### 5. MIN_TVRZAP / MAX_TVRZAP

Фронт должен показывать готовый `warehouseBreakdown[].orderPolicy`, а не
самостоятельно толковать числа:

- `MIN=0` — `DO_NOT_ORDER`, на этот склад не заказывать;
- `MIN>0` — `FORECAST_PLUS_MINIMUM_STOCK`, весь MIN добавляется сверх прогноза,
  включая 1 и 0,001 (уточнение владельца 2026-09-12; см. `FOLIO_PRODUCT_ANALYTICS_API.md`);
- `MAX>=9999` — максимум не ограничен;
- `MAX<9999` — жёсткий верхний предел будущего запаса;
- противоречивые/отрицательные значения — проблема данных, заказ не считать.

`rows[].networkOrderPolicy` отдельно применяет склад 7. При
`status=BLOCKED_BY_KYIV_OPT` товар нельзя заказывать для сети. При `null`,
`DATA_ISSUE` или неготовом snapshot автоматическую рекомендацию формировать
нельзя.

## Финансовые показатели

Использовать готовые `rows[].metrics`, общие `totals.metrics` и вклад складов
`warehouseBreakdown[].metrics`:

- физический, резервный и доступный остаток;
- стоимость остатка/вложенный капитал;
- продажи, выручка, себестоимость и валовая прибыль;
- возвраты отдельными количеством и выручкой;
- регулярные и разовые продажи отдельно;
- средняя стоимость запаса;
- оборачиваемость, GMROI, валовая маржа и покрытие в днях;
- ABC-класс по выбранной базе `REVENUE`, `GROSS_PROFIT` или `SOLD_UNITS`.

Итоги относятся ко всей отфильтрованной выборке, не только текущей странице.
Для нескольких складов коэффициенты уже пересчитаны backend из общих сумм.
Нельзя усреднять готовые проценты по строкам.

`includeReturns=true` пока не даёт return COGS. Поэтому `grossProfit` нельзя
подписать как «чистая прибыль после возвратов»; показывать warning
`RETURN_COGS_NOT_AVAILABLE`.

## Ошибки и предупреждения

- любое условие с `supported=false` не отправлять;
- HTTP 400 — ошибка запроса/неподдержанный фильтр, показать `code` и `message`;
- HTTP 409 `SNAPSHOT_NOT_READY`, `ANALYTICS_SCHEMA_TOO_OLD` или
  `INCOMPATIBLE_GENERATIONS` — предложить перестроить/дождаться snapshot;
- HTTP 503 — временная недоступность, разрешить повтор;
- `warnings` успешного ответа не скрывать;
- неизвестное значение не заменять нулём;
- `facets` относятся к выбранным складам до фильтрации отчёта, на это указывает
  `FACETS_SCOPE_SELECTED_WAREHOUSES`.

## Что пока не реализовывать как рабочую функцию

- бренд;
- менеджер продаж;
- дополнительные штрихкоды `SCL_CODE`;
- source/destination склад перемещения;
- lead time, открытые заказы поставщику, подтверждённое ожидаемое количество;
- финальное рекомендуемое количество закупки;
- XYZ и подневный stockout;
- чистую прибыль после возвратов;
- автоматическое определение бренда по поставщику.

Такие блоки можно показать только как «источник ещё не подтверждён», без
расчётной эвристики.

## Файлы WordPress для реализации

Основные точки изменения в проекте `public`:

- `wp-content/plugins/lavka-price-sync/inc/product-analytics.php`;
- `wp-content/plugins/lavka-price-sync/inc/analytics-scenarios.php`;
- `wp-content/plugins/lavka-price-sync/assets/product-analytics.js`;
- `wp-content/plugins/lavka-price-sync/assets/product-analytics.css`;
- `wp-content/plugins/lavka-price-sync/lavka-price-sync.php`.

Сначала проверить существующую реализацию: не переписывать уже готовые
фильтры и сценарии без необходимости. Добавить v4 как расширение текущего
экрана.

## Критерии приёмки фронта

1. Browser обращается только к WordPress proxy с nonce и capability check.
2. `capabilities` вызывается при смене набора складов.
3. Неподдержанные фильтры скрыты/заблокированы и не отправляются.
4. Отчёт работает только со schema v4; старый snapshot объясняется пользователю.
5. Поиск находит контрольный SKU по основному GTIN.
6. `sellerCodes` нигде не отправляется; поставщик не подписан продавцом или
   менеджером.
7. Фильтры видов операций/движений не меняют текущий остаток.
8. Для нескольких складов totals и ratios берутся из backend.
9. `MIN=0`, сетевой запрет склада 7 и ограничение MAX показаны раздельно.
10. Подтверждённый транзит уменьшает потребность только через
    `availableForPlanningQuantity`; `MIXED_ORIGIN` и неизвестный транзит — нет.
11. Пагинация использует `nextCursor`, сортировка и фильтры не меняются между
    страницами.
12. Проверены минимум 10 SKU вручную: остатки, поставщик, GTIN, продажи,
    валовая прибыль, min/max и товар в пути.

## Что прислать backend после реализации

- перечень изменённых WordPress-файлов и commit;
- примеры запросов `capabilities` и `query` через WordPress proxy;
- скриншоты состояний: schema old/not ready, GTIN search, network blocked,
  confirmed transit, mixed/unknown transit;
- результаты ручной сверки 10 SKU;
- список оставшихся расхождений без попытки скрыть их fallback-эвристикой.
