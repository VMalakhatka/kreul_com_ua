# API перерасчёта учётных цен ФОЛИО

## Назначение и границы первого этапа

API вызывает штатный точечный route ФОЛИО `dbo.i_uchet_add` с `mode=2`. Java
не вычисляет себестоимость собственной формулой и не выполняет прямой `UPDATE`
цены.

Предусмотрены два сценария:

- проверка или перерасчёт одного товара на одном складе;
- фоновая проверка или полный перерасчёт товаров склада.

Здесь слово «полный» означает **обход всех SKU выбранного склада одним Java
job**, а не полную семантику кнопки перерасчёта в настольном ФОЛИО. Точечный и
полный apply используют один и тот же `i_uchet_add(mode=2)`.

### Что именно пересчитывается

`i_uchet_add(mode=2)` заново собирает итоговые показатели товара и его учётную
цену из уже сохранённых учитываемых движений, в том числе из существующих
`SCL_MOVE.KOLC_PREDM`, `SCL_MOVE.SUM_UCHET` и `SCL_MOVE.SUM_UCVAL`.

Этот route **не эквивалентен** полному `I_UCHET_TOVAR` / кнопке полного
перерасчёта ФОЛИО:

- не восстанавливает `SCL_MOVE.SUM_UCHET` и `SUM_UCVAL` приходных строк из
  `SUM_PREDM`, налогов и курсов;
- использует сохранённые учётные суммы приходов как исходные данные для
  восстановления итогов и может штатно пересчитать зависимые расходные строки.

Поэтому endpoint подходит для восстановления **итоговой учётной цены карточки**
при корректных учётных суммах приходов. Если подозрение относится к
`SUM_UCHET/SUM_UCVAL` приходной строки, нужен отдельный диагностический отчёт и
штатный полный workflow ФОЛИО; данный API нельзя выдавать за его замену.

На первом этапе применение разрешено только для подтверждённого режима
**средней учётной цены**. Товары с неподдерживаемой настройкой не изменяются и
возвращаются в предупреждениях.

Все маршруты административные. Их нельзя вызывать напрямую из публичного
браузера. Перед включением записи необходимо проверить существующую внешнюю
аутентификацию и авторизацию `/admin`.

## Безопасность

Ключевые правила:

1. `previewOnly=true` работает строго на чтение: не запускает изменяющую
   процедуру, не пишет в `TMP_MOVE` и не меняет таблицы ФОЛИО.
2. Весь API закрыт отдельным `api-enabled`, по умолчанию выключенным до
   подтверждения внешней авторизации `/admin`.
3. Реальное применение закрыто дополнительными apply-флагами, которые также по
   умолчанию выключены. Для full apply нужен отдельный второй флаг.
4. Перед каждым применением Java повторно проверяет хронологический остаток.
5. Точечный и полный запуск используют общий эксклюзивный mutex: параллельные
   перерасчёты через Java не допускаются.
6. В полном режиме каждый SKU обрабатывается в отдельной транзакции. Ошибка
   одного товара не откатывает уже успешно обработанные товары.
7. Java не может заблокировать запуск перерасчёта из настольного клиента
   ФОЛИО. Реальный полный проход следует выполнять в согласованное окно, когда
   оператор не запускает эту же операцию вручную.

## 1. Один товар

```http
POST /admin/folio/accounting-prices/recalculate
Content-Type: application/json
```

### Запрос

```json
{
  "sku": "ЕВ-МАСЛ-РАДИАТОР",
  "warehouseId": 12,
  "previewOnly": true
}
```

Все три поля обязательны.

| Поле | Тип | Правило |
|---|---|---|
| `sku` | string | точный `COD_ARTIC`, после `trim`; не пустой, не более 20 байт в Windows-1251 |
| `warehouseId` | integer | существующий `SCLAD_R.ID_SCLAD`, значение больше нуля |
| `previewOnly` | boolean | `true` — только диагностика; `false` — штатный перерасчёт, если применение включено |

`previewOnly=true` проверяет товар, конфигурацию склада, область учётной группы,
движения и первую дату отрицательного хронологического остатка. Это не
«перерасчёт с последующим rollback»: изменяющая процедура вообще не вызывается.
Поэтому preview подтверждает возможность запуска и показывает текущее
состояние, но не обещает точное значение будущей цены.

При `previewOnly=false` Java:

1. получает эксклюзивное право на перерасчёт;
2. повторяет precheck внутри операции;
3. вызывает подтверждённый штатный exact-route ФОЛИО для SKU;
4. читает состояние после вызова;
5. фиксирует транзакцию только после успешного postcheck.

### Postcheck перед commit

После штатного вызова Java не ограничивается проверкой отсутствия SQL exception.
Она повторно читает область и откатывает транзакцию, если нарушен хотя бы один
контроль:

- не изменилась структура движений, их идентификаторы, даты, направления,
  количества и число строк;
- не изменились `NACH_KOLCH`, физический `KON_KOLCH`, свободный `REZ_KOLCH`,
  а также начальные учётные цены `UCHET_0_C` и `UCHET_0_VL`;
- для одиночного склада итоговое учётное количество совпадает с хронологией:
  начальное количество + приходы − расходы;
- рублёвый итог соответствует знаковой формуле
  `NACH_KOLCH × UCHET_0_C + Σ(+П SUM_UCHET − Р SUM_UCHET)`;
- валютный итог соответствует аналогичной формуле с `UCHET_0_VL` и
  `SUM_UCVAL`;
- при положительном учётном количестве рублёвая и валютная цены равны
  соответствующей итоговой сумме, делённой на количество, с допуском на
  погрешность legacy `float`.

`SUM_UCHET/SUM_UCVAL` расходных строк не объявляются неизменными: точечный
штатный route может их пересчитать. Защищаются структура и количество движений,
а денежный результат проверяется по формулам выше. Если `TMP_MOVE` осталась
непустой или postcheck не прошёл, транзакция SKU откатывается.

Если `SCLAD_R.N_4` склада не равен `NULL` (в том числе если он равен `0`),
preview возвращает всю обнаруженную область в `affectedWarehouseIds`. Но exact group-route пока не
прошёл отдельный golden-master: на первом этапе такой товар получает
`ACCOUNTING_GROUP_UNSUPPORTED`, а изменяющая процедура не вызывается. Полный
запуск для сгруппированного склада завершается `FAILED` до обхода SKU.

### Успешный preview

```json
{
  "ok": true,
  "previewOnly": true,
  "status": "PREVIEW_READY",
  "sku": "ЕВ-МАСЛ-РАДИАТОР",
  "requestedWarehouseId": 12,
  "affectedWarehouseIds": [12],
  "accountingMethod": {
    "rawCode": 1000,
    "calculationMode": 0,
    "periodMode": 0,
    "includeTax": false,
    "name": "AVERAGE"
  },
  "eligibleToApply": true,
  "procedureExecuted": false,
  "before": [
    {
      "warehouseId": 12,
      "warehouseName": "ФКальмиус",
      "initialQuantity": 0,
      "physicalQuantity": 121,
      "availableQuantity": 121,
      "accountingQuantity": 121,
      "accountingAmount": 12000134120,
      "accountingCurrencyAmount": 0,
      "accountingPrice": 99174662.14876033,
      "accountingCurrencyPrice": 0,
      "initialAccountingPrice": 800,
      "initialAccountingCurrencyPrice": 0,
      "accountedMovementCount": 2,
      "accountedMovementQuantity": 121,
      "accountedMovementAmount": 12000134120,
      "accountedMovementCurrencyAmount": 0
    }
  ],
  "after": [],
  "warnings": [],
  "errors": []
}
```

Пустой массив `after` у preview означает, что данные не менялись. Нельзя
отображать `before` как обещанную цену после будущего применения.

`accountedMovementQuantity` — контрольная сумма количеств учитываемых движений
этого склада. `accountedMovementAmount` и
`accountedMovementCurrencyAmount` — агрегаты уже сохранённых
`SUM_UCHET/SUM_UCVAL`, а не независимо вычисленная «правильная» стоимость.

### Успешное применение

```json
{
  "ok": true,
  "previewOnly": false,
  "status": "RECALCULATED",
  "sku": "ЕВ-МАСЛ-РАДИАТОР",
  "requestedWarehouseId": 12,
  "affectedWarehouseIds": [12],
  "accountingMethod": {
    "rawCode": 1000,
    "calculationMode": 0,
    "periodMode": 0,
    "includeTax": false,
    "name": "AVERAGE"
  },
  "eligibleToApply": true,
  "procedureExecuted": true,
  "priceChanged": true,
  "before": [
    {
      "warehouseId": 12,
      "warehouseName": "ФКальмиус",
      "initialQuantity": 0,
      "physicalQuantity": 121,
      "availableQuantity": 121,
      "accountingQuantity": 121,
      "accountingAmount": 121,
      "accountingCurrencyAmount": 0,
      "accountingPrice": 1,
      "accountingCurrencyPrice": 0,
      "initialAccountingPrice": 800,
      "initialAccountingCurrencyPrice": 0,
      "accountedMovementCount": 2,
      "accountedMovementQuantity": 121,
      "accountedMovementAmount": 12000134120,
      "accountedMovementCurrencyAmount": 0
    }
  ],
  "after": [
    {
      "warehouseId": 12,
      "warehouseName": "ФКальмиус",
      "initialQuantity": 0,
      "physicalQuantity": 121,
      "availableQuantity": 121,
      "accountingQuantity": 121,
      "accountingAmount": 12000134120,
      "accountingCurrencyAmount": 0,
      "accountingPrice": 99174662.14876033,
      "accountingCurrencyPrice": 0,
      "initialAccountingPrice": 800,
      "initialAccountingCurrencyPrice": 0,
      "accountedMovementCount": 2,
      "accountedMovementQuantity": 121,
      "accountedMovementAmount": 12000134120,
      "accountedMovementCurrencyAmount": 0
    }
  ],
  "warnings": [],
  "errors": []
}
```

`before` и `after` являются массивами, потому что учётная группа может
охватывать несколько складов.

Возможные статусы точечного результата:

| Статус | Значение |
|---|---|
| `PREVIEW_READY` | read-only проверка завершена, препятствий не найдено |
| `PREVIEW_BLOCKED` | preview нашёл предупреждение или ошибку, запрещающие apply |
| `RECALCULATED` | штатная процедура выполнена; изменение `UCHET_CENA`/`UCHET_VALT` указано в `priceChanged` |
| `BLOCKED` | apply не запущен, потому что повторный precheck обнаружил препятствие |

`RECALCULATED` вместе с `priceChanged=false` является нормальным идемпотентным
результатом: процедура выполнена, но `UCHET_CENA` и `UCHET_VALT` уже были
правильными. Изменение только производных сумм строк не делает `priceChanged=true`.

Пример предупреждения об отрицательном остатке:

```json
{
  "code": "NEGATIVE_CHRONOLOGICAL_STOCK",
  "message": "The chronological stock becomes negative; Folio cannot safely recalculate this product",
  "details": {
    "warehouseId": 12,
    "recno": 12547283,
    "documentDate": [2017, 4, 22, 0, 0],
    "runningQuantity": -21
  }
}
```

Основные диагностические коды:

| Код | Что означает | Применение |
|---|---|---|
| `NEGATIVE_CHRONOLOGICAL_STOCK` | в истории появляется отрицательный остаток | запрещено |
| `RETURN_MOVEMENT_REQUIRES_REVIEW` | есть возврат; упрощённый precheck не подменяет точную ветку ФОЛИО | запрещено |
| `ZERO_QUANTITY_ACCOUNTED_MOVEMENT` | учитываемое движение имеет нулевое количество | запрещено |
| `MOVEMENT_DATE_MISSING` | у учитываемого движения нет даты | запрещено |
| `NON_INTEGRAL_TECHNICAL_KEY` | legacy float-ключ документа не является целым числом | запрещено |
| `ACCOUNTING_METHOD_UNSUPPORTED` | режим не относится к подтверждённой средней цене | запрещено |
| `ACCOUNTING_GROUP_UNSUPPORTED` | у склада заполнен `N_4` (включая `0`) либо обнаружен общий effective scope; group-route ещё не подтверждён экспериментом | запрещено |
| `ACCOUNTING_GROUP_SETTINGS_MISMATCH` | настройки складов одной учётной группы различаются | запрещено |
| `HIDDEN_PRODUCT_TYPE` | ФОЛИО исключает этот тип товара из перерасчёта | запрещено |
| `TMP_MOVE_NOT_EMPTY` | служебная таблица занята или содержит остаточные строки | запрещено |

Любой элемент `warnings` или `errors` делает `eligibleToApply=false`. Для
точечного apply это даёт HTTP `409` и `status=BLOCKED` без вызова процедуры.

## 2. Полный перерасчёт склада

```http
POST /admin/folio/accounting-prices/recalculate/full
Content-Type: application/json
```

Полный проход выполняется в фоне, чтобы длинный расчёт не удерживал HTTP
соединение и не завершался ошибкой reverse proxy.

### Запрос

```json
{
  "warehouseId": 12,
  "previewOnly": true,
  "continueOnNegativeStock": true
}
```

| Поле | Обязательный | Правило |
|---|---:|---|
| `warehouseId` | да | склад, товары которого входят в проход |
| `previewOnly` | да | `true` — полный read-only precheck; `false` — применение при включённом feature flag |
| `continueOnNegativeStock` | нет | по умолчанию `true`; пропускать проблемный SKU и продолжать |

Ответ на принятый запуск возвращается сразу с HTTP `202 Accepted`:

```json
{
  "ok": true,
  "accepted": true,
  "running": true,
  "jobId": "d4ea6e47-d4ba-4d05-8b81-9090647f84f5",
  "status": "QUEUED",
  "request": {
    "warehouseId": 12,
    "previewOnly": true,
    "continueOnNegativeStock": true
  },
  "startedAt": "2026-08-15T18:10:03.145",
  "totalProducts": 0,
  "processedProducts": 0,
  "eligibleProducts": 0,
  "recalculatedProducts": 0,
  "priceChangedProducts": 0,
  "skippedProducts": 0,
  "warningCount": 0,
  "warningsTruncated": false,
  "warnings": []
}
```

Повторный запуск, пока уже выполняется точечный или полный перерасчёт,
отклоняется с HTTP `409 Conflict`. Для полного POST тело содержит текущий
`FolioAccountingPriceFullStatusResponse` с `accepted=false`; для занятого
точечного endpoint единый error response содержит код
`ACCOUNTING_PRICE_RECALCULATION_BUSY`.

Если full POST пришёл во время точечного перерасчёта, отдельного full `jobId`
ещё нет:

```json
{
  "ok": false,
  "accepted": false,
  "running": true,
  "status": "BUSY",
  "totalProducts": 0,
  "processedProducts": 0,
  "eligibleProducts": 0,
  "recalculatedProducts": 0,
  "priceChangedProducts": 0,
  "skippedProducts": 0,
  "warningCount": 0,
  "warningsTruncated": false,
  "warnings": [],
  "error": "A point Folio accounting-price recalculation is already running"
}
```

### Поведение при отрицательном остатке

При `continueOnNegativeStock=true`:

- проблемный SKU не изменяется;
- в job добавляется `NEGATIVE_CHRONOLOGICAL_STOCK`;
- обработка продолжается со следующего SKU;
- job может завершиться успешно со статусом `COMPLETED_WITH_WARNINGS`.

При `continueOnNegativeStock=false` job останавливается на первом таком товаре.
Транзакция проблемного SKU откатывается, но ранее успешно пересчитанные SKU не
откатываются, потому что полный проход намеренно не является одной общей
транзакцией.

## 3. Статус полного прохода

```http
GET /admin/folio/accounting-prices/recalculate/full/status
```

Endpoint возвращает текущий либо последний полный запуск:

```json
{
  "ok": true,
  "accepted": false,
  "running": true,
  "jobId": "d4ea6e47-d4ba-4d05-8b81-9090647f84f5",
  "status": "RUNNING",
  "request": {
    "warehouseId": 12,
    "previewOnly": false,
    "continueOnNegativeStock": true
  },
  "startedAt": "2026-08-15T18:10:03.145",
  "totalProducts": 14320,
  "processedProducts": 731,
  "eligibleProducts": 725,
  "recalculatedProducts": 709,
  "priceChangedProducts": 163,
  "skippedProducts": 6,
  "currentSku": "KR-16234",
  "warningCount": 6,
  "warningsTruncated": false,
  "warnings": [
    {
      "code": "NEGATIVE_CHRONOLOGICAL_STOCK",
      "message": "The chronological stock becomes negative; Folio cannot safely recalculate this product",
      "details": {
        "sku": "CON-100516109R",
        "warehouseId": 12,
        "recno": 12547283,
        "documentDate": [2017, 4, 22, 0, 0],
        "runningQuantity": -21
      }
    }
  ]
}
```

В ответе `GET` поле `accepted` всегда равно `false`: оно имеет смысл только в
непосредственном ответе `POST`, принявшего новую задачу. Поля со значением
`null` не сериализуются. `startedAt` и `completedAt` в status имеют строковый
формат `yyyy-MM-dd'T'HH:mm:ss.SSS`; даты внутри свободного `details` warning
сохраняют стандартное представление Jackson проекта.

Статусы фонового запуска:

- `IDLE` — после старта приложения полный запуск ещё не выполнялся;
- `BUSY` — точечный перерасчёт уже занял общий слот, поэтому full job не принят;
- `QUEUED` — запрос принят в очередь;
- `RUNNING` — товары обрабатываются;
- `COMPLETED` — проход закончен без предупреждений;
- `COMPLETED_WITH_WARNINGS` — закончен, но отдельные SKU пропущены;
- `STOPPED_ON_NEGATIVE_STOCK` — остановлен правилом
  `continueOnNegativeStock=false`;
- `FAILED` — системная ошибка произошла до успешной обработки товаров;
- `FAILED_PARTIAL` — системная ошибка произошла после того, как часть SKU уже
  была обработана и могла быть зафиксирована.

`warnings` предназначен для отчёта оператору. Интерфейс должен показывать
количество пропусков и позволять скачать/скопировать список SKU; нельзя скрывать
предупреждения за общим зелёным статусом.

В полном status список `warnings` является сводным: в него попадают как
`warnings`, так и `errors` точечных проверок, а в `details` Java дополнительно
добавляет `sku`. Обычный warning блокирует изменение конкретного SKU и позволяет
перейти к следующему. Любой `error` является системным стоп-условием и завершает
job как `FAILED`/`FAILED_PARTIAL`. `continueOnNegativeStock=false` дополнительно
останавливает job на warning `NEGATIVE_CHRONOLOGICAL_STOCK`.

Рекомендуемый цикл интерфейса:

1. отправить `POST` один раз;
2. при HTTP `202` сохранить `jobId` и опрашивать status раз в 2–5 секунд;
3. продолжать polling, пока `running=true`;
4. при финальном статусе показать итоговые счётчики и warnings;
5. не отправлять повторный POST как способ polling.

Cancel endpoint на первом этапе отсутствует.

## Как это соотносится со штатным интерфейсом ФОЛИО

SQL-процедура не умеет показывать диалог и сама не задаёт оператору вопрос.
При отрицательной истории она возвращает вызывающему коду проблемный товар,
дату и позицию продолжения. Уже настольный клиент ФОЛИО показывает сообщение и
решает, запускать ли следующий фрагмент.

Документация и разбор procedures подтверждают сигнал об отрицательном остатке,
но не подтверждают точный текст и набор кнопок каждой версии настольного
клиента. Поэтому Java не имитирует неизвестный диалог: выбор явно передаётся в
`continueOnNegativeStock`.

Java не продолжает диапазон через `I_UCHET_1_TOVAR` вслепую: один такой вызов
может успеть изменить несколько предшествующих товаров до warning. Вместо этого
полный job сам получает упорядоченный список SKU, делает отдельный precheck и
для разрешённого товара вызывает подтверждённый точечный route
`dbo.i_uchet_add` с `mode=2`.

## Коды HTTP

| HTTP | Код | Причина |
|---:|---|---|
| 400 | `VALIDATION_ERROR` | отсутствует поле или неверен формат |
| 403 | `ACCOUNTING_PRICE_API_DISABLED` | весь модуль, включая preview POST, выключен до подтверждения защиты `/admin` |
| 403 | `ACCOUNTING_PRICE_APPLY_DISABLED` | точечный apply выключен конфигурацией |
| 403 | `ACCOUNTING_PRICE_FULL_APPLY_DISABLED` | полный apply выключен конфигурацией |
| 404 | `FOLIO_PRODUCT_NOT_FOUND` | пара SKU + склад отсутствует |
| 404 | `FOLIO_WAREHOUSE_NOT_FOUND` | склад отсутствует |
| 409 | `ACCOUNTING_PRICE_RECALCULATION_BUSY` | точечный перерасчёт уже выполняется; full POST при занятости возвращает status body с `accepted=false` |
| 409 | response `status=BLOCKED` | точечное применение заблокировано; причина находится в `warnings`/`errors` |
| 503 | стандартная database error | недоступна ФОЛИО или штатная процедура завершилась SQL-ошибкой |
| 500 | стандартная unhandled error | postcheck не пройден; транзакция SKU откатилась |

Ошибочный ответ использует существующий единый формат Java API и содержит
`reqId`, который нужно показывать в технических деталях обращения.

## Feature flags и ввод в эксплуатацию

Рекомендуемая последовательность:

1. после деплоя оставить `api-enabled` и оба apply-флага выключенными;
2. подтвердить внешнюю защиту `/admin`, затем включить только `api-enabled`;
3. выполнить точечный `previewOnly=true` на проверенных товарах;
4. выполнить полный `previewOnly=true`, разобрать все пропуски;
5. включить точечное применение и сверить один товар с интерфейсом ФОЛИО;
6. сделать резервную копию и только затем отдельно включить полный apply;
7. первый полный apply запускать в окно без ручного перерасчёта.

Используются следующие настройки:

```properties
lavka.folio.accounting-prices.api-enabled=${LAVKA_FOLIO_ACCOUNTING_PRICE_API_ENABLED:false}
lavka.folio.accounting-prices.apply-enabled=${LAVKA_FOLIO_ACCOUNTING_PRICE_APPLY_ENABLED:false}
lavka.folio.accounting-prices.full-apply-enabled=${LAVKA_FOLIO_ACCOUNTING_PRICE_FULL_APPLY_ENABLED:false}
lavka.folio.accounting-prices.lock-timeout-ms=${LAVKA_FOLIO_ACCOUNTING_PRICE_LOCK_TIMEOUT_MS:5000}
lavka.folio.accounting-prices.query-timeout-seconds=${LAVKA_FOLIO_ACCOUNTING_PRICE_QUERY_TIMEOUT_SECONDS:120}
lavka.folio.accounting-prices.max-reported-warnings=${LAVKA_FOLIO_ACCOUNTING_PRICE_MAX_REPORTED_WARNINGS:200}
lavka.folio.accounting-prices.zone=${LAVKA_FOLIO_ACCOUNTING_PRICE_ZONE:Europe/Kyiv}
```

`api-enabled` разрешает все три маршрута модуля, включая preview и status; его безопасное
значение по умолчанию — `false`. После подтверждения внешней защиты `/admin`
его можно включить, сохранив apply-флаги выключенными.

`apply-enabled` разрешает точечный apply. Для полного apply должны одновременно
быть `true` оба флага: `apply-enabled` и `full-apply-enabled`. Full apply намеренно
остаётся выключенным по умолчанию даже после включения preview и точечного
режима. Feature flags являются защитой от случайного запуска и не заменяют
авторизацию.

`query-timeout-seconds` ограничивает одну read/write-транзакцию и один вызов
legacy procedure. Для full job это лимит **на один SKU**, а не на весь фоновый
проход. Тайм-аут точечного apply приводит к rollback; в full job — к `FAILED`
или `FAILED_PARTIAL` в зависимости от уже обработанных товаров.

`max-reported-warnings` ограничивает массив `warnings` в status, но полный счётчик
остаётся в `warningCount`; признак усечения передаётся в `warningsTruncated`.

## Технические ограничения

- ФОЛИО работает на старом SQL Server и через jTDS; endpoint не должен
  использовать современные SQL-конструкции, отсутствующие в SQL Server 2000.
- Штатный алгоритм использует постоянную служебную таблицу `TMP_MOVE`. Поэтому
  пересчёты сериализуются; запуск параллельно с настольным ФОЛИО остаётся
  внешним операционным риском.
- Отрицательный остаток проверяется по хронологии учитываемых движений, а не по
  одному текущему `KON_KOLCH`.
- Нельзя полагаться только на отсутствие SQL exception: после вызова Java
  проверяет `TMP_MOVE`, защищённые остатки, структуру движений и денежные
  postconditions. OUT-суммы procedure пока не используются как отдельный
  критерий успеха, потому что их семантика не подтверждена для всех веток.
- Полный apply частично фиксируемый: каждая успешная позиция commit-ится
  отдельно. Это позволяет продолжать после плохого товара, но исключает
  атомарный rollback всего склада.
- Состояние фоновой задачи хранится в памяти процесса Java. После рестарта
  status снова будет `IDLE`; незавершённая задача автоматически не
  возобновляется. Уже зафиксированные транзакции отдельных SKU сохраняются.
- Поддержка учётных групп, LIFO, FIFO, фиксированной цены и партий требует
  отдельных golden-master экспериментов на `Paint_Rus` и не включается
  автоматически.

## Источники подтверждения

- определения штатных procedures текущей версии ФОЛИО;
- руководство ФОЛИО о предупреждении при отрицательном остатке;
- воспроизводимый rollback-эксперимент на копии `Paint_Rus` для точечного
  штатного route;
- текущая схема `SCL_ARTC`, `SCL_MOVE`, `SCLAD_R` и поведение `TMP_MOVE`.

Ни один пример этого документа не является разрешением запускать apply в
рабочей `Paint_Ua` без backup, включённого feature flag и операционного окна.
