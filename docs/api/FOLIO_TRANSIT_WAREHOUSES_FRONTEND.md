# Настраиваемые транспортные склады — согласованный контракт Java → WordPress

Дата: 2026-09-06. Реализация backend: `FolioTransitAnalytics`,
`FolioProductAnalyticsService`, `FolioProductAnalyticsDao`.
Дополняет [основной API](FOLIO_PRODUCT_ANALYTICS_API.md) и заменяет ограничение
«только склад 9» в старом handoff v4. Фронтенд в этой задаче не изменялся.

## Развёртывание и совместимость

Новые routes, флаги apply и SQL-процедуры не нужны. Используются существующие:

- `POST /admin/folio/product-analytics/capabilities`;
- `POST /admin/folio/product-analytics/query`.

Нужен деплой Java. Структура snapshot не меняется от этой доработки транзита:
отдельной новой миграции нет, `analyticsSchemaVersion=5` сохраняется,
версия вычисления транзита — `calculationVersion=2`.
Весь текущий набор изменений также содержит ранее добавленную V13 для наличия.
Не путать эту миграцию с выбором транспортных складов.

У каждого транспортного источника должен быть ACTIVE snapshot актуальной схемы.
Если его нет/он старый, обновить отдельно через
`POST /admin/folio/accounting-prices/snapshot/refresh`, последовательно по складам.
Кампания переучёта для этого не требуется. Неподдерживаемый `N_2` автоматически
не менять. Отчёты читают только MariaDB, не живую ФОЛІО.

Семантика ввода:

| Ввод | Результат |
|---|---|
| нет `calculation.transit` | legacy `[9]` |
| нет `transit.warehouseIds` / null | legacy `[9]` |
| `warehouseIds: [9]` | тот же источник, что legacy |
| `warehouseIds: [9,10]` | независимые источники 9 и 10 |
| `warehouseIds: []` | транзит отключён, не fallback к 9 |

WordPress берёт список server-side из глобальной `lavka_transit_warehouse_ids`,
не из копии сценария и не по названию склада. Повторы удаляются, ID сортируются.
Допустимы 0–16 уникальных положительных целых ID; null/отрицательные/0 внутри
списка запрещены. Защитный предел исходного списка до нормализации — 256 элементов.

## Одинаковый блок запроса для capabilities и query

```json
{
  "sourceDatabase": "Paint_Ua",
  "warehouseIds": [1, 7],
  "calculation": {
    "transit": {
      "warehouseIds": [9, 10],
      "configurationRevision": "11e07e7bb591992307e25655831247578120a67b9436b172b61a4df9ba743557"
    }
  }
}
```

Для `/query` дополнительно нужны прежние `period`, page/sort и другие отборы.
Верхнеуровневый `warehouseIds` остаётся областью продаж/экономики. Транспортные
ID не добавляются туда автоматически, не расширяют продажи, ABC, facets или
фильтры движений. Для `/capabilities` из calculation обрабатывается transit.

Ревизия: SHA-256 UTF-8 компактного JSON **нормализованного массива ID**.
Для `[10,9,9]` сначала получается `[9,10]`, без пробелов/перевода строки, затем
хэш выше. В PHP: `hash('sha256', wp_json_encode(array_values($sorted_unique_ids)))`.
ID должны быть числами, не строками. Ревизию можно не передавать — Java вычислит
её и вернёт. Если передана, она обязана совпасть (hex-регистр несущественен).
Хэш другой структуры/списка — HTTP 400 `TRANSIT_CONFIGURATION_REVISION_MISMATCH`.
Прочие ошибки списка — HTTP 400 `INVALID_TRANSIT_CONFIGURATION`.

## Capabilities и context

После деплоя capability-gate:

- `features.configurableTransit.supported=true`;
- `transit.configurable=true`, `transit.calculationVersion=2`;
- `transit.maxWarehouseCount=16`.

`transit` содержит `enabled`, `warehouseIds`, `configurationRevision`, `ready`,
`unavailableReason`, `warnings` и `sources[]`. Каждый источник:

```json
{
  "warehouseId": 9,
  "warehouseName": "Транспорт",
  "generationId": 123,
  "asOf": "2026-09-06",
  "completedAt": "2026-09-06T02:15:00.000",
  "analyticsSchemaVersion": 5,
  "ready": true,
  "unavailableReason": null
}
```

ID/даты примера условные. Источник без ACTIVE возвращается отдельно с
`SNAPSHOT_NOT_READY`, старая схема — `ANALYTICS_SCHEMA_TOO_OLD`.
Отсутствие дат ACTIVE снимка — `INCOMPLETE_SNAPSHOT_METADATA`.
Нет снимка — ещё не доказательство, что самого склада не существует; Java
не обращается к живому справочнику ради этой проверки. Неподдерживаемый режим
покажет snapshot/refresh, режим менять нельзя.

`ready` capabilities — готовность набора снимков, не доказательство происхождения
каждого SKU. Если один снимок отсутствует, общий ready=false, у остальных
сохраняется своя диагностика. При `[]`: enabled=false, ready=true, sources=[].

Та же структура возвращается в `query.context.transit`. Применённый
нормализованный запрос — `appliedFilters.calculation.transit`.

## Строка товара: rows[].inTransitStock

Это общий объект с:

- `calculationVersion=2`, `enabled`, `ready`;
- `warehouseIds`, `configurationRevision`;
- `status`, `supplierOriginConfirmed`;
- `availableForPlanningQuantity` — **полный подтверждённый** доступный транзит;
- `knownAvailableForPlanningQuantity` — известная часть только для диагностики;
- `sources[]` — детали каждого физического транспортного склада;
- `warnings`.

`sources[]` содержит прежние поля источника:
warehouseId/warehouseName/generationId, physicalQuantity/reservedQuantity/
availableQuantity/availableForPlanningQuantity, openingQuantityAtHorizon,
supplierOriginConfirmed/status, lastSupplierReceiptDate/suppliers;
добавлены asOf/completedAt/warnings.

При одном источнике старые скалярные warehouseId/warehouseName/generationId,
остатки, suppliers и lastSupplierReceiptDate остаются также на верхнем уровне
inTransitStock. При нескольких источниках единые warehouseId/generationId = null:
старый клиент не должен принять их за склад 9. Поставщиков читать из sources,
не складывать исторические `receiptQuantityInHorizon`.
Физический/резерв/доступный итог суммируются только при известных значениях
во всех источниках; они не заменяют подтверждённый planning quantity.

### Подтверждение происхождения

Положительный остаток разрешён только при выполнении всех проверок:

1. Есть карточка нужного SKU в нужном sourceDatabase/warehouse/generation.
2. Остаток, резерв и доступность известны, неотрицательны и
   `physicalQuantity - reservedQuantity = availableQuantity`.
3. Восстановленный входящий остаток до горизонта равен 0.
4. Внутри горизонта есть положительные stock-affecting приходы, и каждый —
   PURCHASE_RECEIPT от типа организации `Т`/`I` с непустым кодом контрагента.

При этом источник получает CONFIRMED_SUPPLIER_ORIGIN, а planning quantity =
его текущий availableQuantity. Никакого `max(неизвестное/отрицательное,0)` нет.
Нулевой физический остаток существующей корректной карточки даёт
NO_IN_TRANSIT_STOCK и подтверждённый 0. Полностью зарезервированный положительный
поставщицкий остаток также даёт доступный объём 0, но сохраняет происхождение
в детализации источника.

| Статус источника | Что означает |
|---|---|
| CONFIRMED_SUPPLIER_ORIGIN | происхождение подтверждено, доступный объём известен |
| NO_IN_TRANSIT_STOCK | подтверждённый нулевой физический остаток |
| SKU_NOT_PRESENT | карточка отсутствует; количество неизвестно, не 0 |
| SNAPSHOT_NOT_READY / ANALYTICS_SCHEMA_TOO_OLD | обновить источник |
| INCOMPLETE_SNAPSHOT_METADATA | нет даты/завершения снимка; обновить источник |
| NEGATIVE_TRANSIT_STOCK | отрицательный остаток, резерв или доступность |
| INCONSISTENT_TRANSIT_BALANCE | физический минус резерв не равен доступному |
| INCOMPLETE_TRANSIT_DATA | неполные поля |
| OPENING_BALANCE_UNATTRIBUTED | ненулевой входящий остаток, происхождение не подтверждено |
| NO_CONFIRMED_INBOUND | нет подтверждённых приходов при положительном остатке |
| MIXED_ORIGIN | не все приходы доказанно поставщицкие |

Для нескольких источников с хотя бы одним неизвестным:
status=INCOMPLETE_TRANSIT_DATA, ready=false, availableForPlanningQuantity=null.
Например, известно 12, второй склад не подтверждён: knownAvailable...=12,
но **вычитать 12 как полный транзит нельзя**. Для одного проблемного источника
верхний status сохраняет его точную причину.

Для подтверждённых 12 + 8 общий объём 20. При общем доступном 0 верхний status
NO_IN_TRANSIT_STOCK; отличать отсутствие от резервов по sources. При `[]`:
status=DISABLED, enabled=false, availableForPlanningQuantity=0 — это настройка
«не учитывать», а не утверждение об отсутствии товара в природе.

## Двойной учёт и закупочная рекомендация

При пересечении транспортных ID с **любым** ID области анализа Java сохраняет
экономический отчёт и физические детали, но блокирует вычитание транзита:
status=TRANSIT_SCOPE_OVERLAP, ready=false, availableForPlanningQuantity=null.
Warning TRANSIT_ALREADY_INCLUDED_IN_ANALYSIS_STOCK. Список не исправляется
молча. Это также относится к legacy-запросу анализа самого склада 9.

Java не добавляет транзит в спрос и не выполняет закупочную формулу. WordPress:

```text
заказ до MOQ = max(0, цель - доступно в группе - выделенный транзит
                     - другие подтверждённые поступления
                     - перемещение в группу + перемещение из группы)
```

Пример: цель 90, доступно 30, транзит 20 → 40, упаковка 10 оставляет 40.
Цель/спрос остаётся 90. Нельзя уменьшить сначала спрос на 20, а затем ещё раз
вычесть 20 из заказа. MIN/MAX/лимиты/MOQ применяются прежним закупочным алгоритмом.

Java не принимает распределение между группами или открытые заказы и не имеет
подтверждённых lot identity/назначения/ETA. Поэтому:

- одна группа получает подтверждённый общий объём один раз;
- при нескольких группах менеджер распределяет его, сумма назначений ≤ общего;
- источники транзита не являются донорами дополнительного перемещения;
- уже принятые на транспорт количества исключить из незакрытых заказов;
- без подтверждённой сверки партий нельзя одновременно вычитать их как транзит
  и открытый заказ. Неизвестность нельзя обойти ручным «0»;
- снимки разных физических складов сняты не атомарно: перед рекомендацией
  проверить их даты и межскладские движения. Совпадение SKU/поставщика само по
  себе не доказывает отсутствие дублирования одной партии.

Возможности `openOrdersDeduplicationSupported=false` и
`automaticGroupAllocationSupported=false` в features — намеренно явные.
Warnings DESTINATION_AND_ETA_NOT_CONFIRMED и OPEN_ORDER_DEDUPLICATION_REQUIRED
не отменяют доказанный физический объём, но запрещают выдавать его за
автоматически распределённую/дедуплицированную закупочную рекомендацию.

## Пагинация и экспорт

Курсор фиксирует normalized transit warehouseIds/revision и **все** поколения
транспортных источников вместе с прежними входами отчёта. Смена состава,
появление отсутствовавшего снимка или смена generation между страницами —
409 ANALYTICS_CURSOR_EXPIRED. Начать заново, не склеивать части старого и нового
экспорта. Изменение имени при одинаковом ID не меняет привязку источника.

WordPress сохраняет context.transit вместе с preview и обоими экспортами CSV/XLSX.
Перед использованием preview сравнивает его revision/ID с текущей настройкой,
при расхождении блокирует рекомендацию. Сортировки/фильтры экономического
отчёта остаются прежними; эта версия добавляет выбор источников и детали,
а не отдельный SQL-фильтр по вычисленному общему транзиту.

## Задание WordPress после деплоя

1. Capability-gated передача calculation.transit из глобальной настройки в оба endpoint.
2. При неподдерживаемом backend не отправлять новый параметр вслепую; сохранить
   старую защиту несовпадения источников, не подставлять `[9]` вместо выбранного списка.
3. После capability confirmation проверять warehouseIds/revision, version2,
   ready/status/planning quantity; заменить проверку единственного warehouseId
   на проверку полного списка и sources[].
4. Показать каждый источник и причину отказа; NULL не показывать как 0.
5. Использовать только полный availableForPlanningQuantity, не knownAvailable...
   и не суммы исторических приходов. Сохранить контроль распределения/дублей.
6. Перезапускать preview/export при смене настройки/поколения. Никаких вызовов apply.

Рекомендуемые skills: lavka-woo + folio-inventory-profit-planning;
для сверки источников — work-with-folio-mssql. Java-изменения не передавались
автоматически другой задаче и не меняли код WordPress.

## Проверено и оставшаяся приёмка

Unit/service-тесты покрывают legacy/default/пустой список, нормализацию/revision,
12+8, резервы, происхождение, отрицательные/несогласованные остатки, отсутствие
карточки/снимка, пересечение областей, неизменность спроса и смену поколения
второго транспортного склада между страницами.

Отдельная одноразовая MariaDB: 1000 исторического прихода в каждом источнике,
расход до физического 14/8 и резерв 2/0 → доступно 12/8 → сумма20. Проверены
изоляция sourceDatabase/generation и запрет подтверждения без кода поставщика.
Бизнес-данные Paint_Ua не изменялись. Перед использованием закупочных рекомендаций
нужна операторская сверка нескольких рабочих SKU и их открытых заказов/партий.
