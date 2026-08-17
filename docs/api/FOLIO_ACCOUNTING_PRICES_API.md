# API перерасчёта учётных цен ФОЛИО

Инструкция по интеграции интерфейса и отображению диагностики:
[FOLIO_ACCOUNTING_PRICES_FRONTEND.md](FOLIO_ACCOUNTING_PRICES_FRONTEND.md).

## Назначение и границы первого этапа

API использует два разных штатных алгоритма ФОЛИО. Точечный маршрут и
`/recalculate/full` вызывают `dbo.i_uchet_add` с `mode=2`. Отдельный
`/recalculate/native-full` управляет полной процедурой `dbo.I_UCHET_TOVAR`.
Java не вычисляет себестоимость собственной формулой и не выполняет прямой
`UPDATE` цены.

Предусмотрены три сценария:

- проверка или перерасчёт одного товара на одном складе;
- Java-обход всех SKU склада через `i_uchet_add(mode=2)`;
- штатный полный перерасчёт ФОЛИО через `I_UCHET_TOVAR`.

Маршрут `/recalculate/full` означает **обход всех SKU выбранного склада одним
Java job**, а не полную семантику кнопки перерасчёта в настольном ФОЛИО.
Точечный маршрут и `/full` используют `i_uchet_add(mode=2)`. Отдельный маршрут
`/recalculate/native-full` управляет именно `I_UCHET_TOVAR`.

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
`SUM_UCHET/SUM_UCVAL` приходной строки, Java-full нельзя выдавать за полный
перерасчёт: следует использовать отдельный `/recalculate/native-full`, который
управляет `I_UCHET_TOVAR`, только после его точного rollback-preview.

На первом этапе применение разрешено только для подтверждённого режима
**средней учётной цены**. Для native-full это точная конфигурация
`SCLAD_R.N_2=1000` при `SCLAD_R.N_4 IS NULL`. Товары и склады с другой
настройкой не изменяются.

Все маршруты административные и доступны только через защищённый VPN/internal
network проекта. Отдельная прикладная авторизация для этого deployment не
требуется; отключение VPN прекращает доступ к API.

## Безопасность

Ключевые правила:

1. Для точечного route и `/full` `previewOnly=true` работает строго на чтение.
   Для `/native-full` точный preview обязан вызвать `I_UCHET_TOVAR`, но каждая
   порция выполняется в отдельной транзакции с обязательным rollback.
2. `api-enabled` оставлен как аварийный server-side выключатель. В текущем
   VPN/internal deployment он по умолчанию включён для status и preview.
3. Реальное применение закрыто дополнительными apply-флагами, которые также по
   умолчанию выключены. Java-full и native-full имеют разные отдельные флаги.
4. Точечный и Java-full режимы проверяют хронологический остаток перед записью
   каждого SKU. Native-full перед apply выполняет отдельный полный проход
   штатной процедуры с rollback всех порций.
5. Точечный и полный запуск используют общий эксклюзивный mutex: параллельные
   перерасчёты через Java не допускаются.
6. В Java-full каждый SKU обрабатывается в отдельной транзакции. Native-full
   обрабатывает диапазоны, размер которых определяет сама legacy-процедура по
   времени выполнения; границы порций preview и apply могут различаться.
7. Java не может заблокировать работу настольного клиента ФОЛИО. И rollback-
   preview, и реальный native-full следует выполнять в согласованное окно, когда
   менеджеры не создают, не сохраняют и не исправляют документы. Rollback
   отменяет данные порции, но не устраняет блокировки во время её выполнения.

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
    "initialQuantity": 10,
    "quantityBefore": 10,
    "operation": {
      "kind": "EXPENSE",
      "documentType": "Р",
      "quantity": 11,
      "recno": 12547283,
      "documentId": 753800,
      "documentNumber": 20042799,
      "documentDate": "2017-04-22T00:00:00",
      "warehouseId": 12
    },
    "quantityAfter": -1,
    "shortageQuantity": 1,
    "movementPosition": 27,
    "movementCount": 184,
    "currentState": {
      "physicalQuantity": 4,
      "availableQuantity": 3,
      "accountingQuantity": 4,
      "accountingPrice": 800
    }
  }
}
```

Это причинная диагностика, а не только текущий остаток. `quantityBefore`
показывает расчётный хронологический остаток непосредственно перед проблемным
движением, `operation` — строку документа, которая впервые сделала остаток
отрицательным, а `quantityAfter` — результат после неё. `currentState` —
текущее состояние карточки товара на момент проверки; его нельзя подменять
историческим остатком после операции.

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

`TMP_MOVE` в legacy-базе может постоянно содержать старые служебные строки. Их
наличие само по себе не является признаком выполняющегося перерасчёта и не
блокирует подтверждённый режим средней цены `N_2=1000`. Очищать `TMP_MOVE`
через API или вручную нельзя. Режимы LIFO/FIFO/партий остаются запрещены до
отдельного golden-master теста.

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
        "initialQuantity": 10,
        "quantityBefore": 10,
        "operation": {
          "kind": "EXPENSE",
          "documentType": "Р",
          "quantity": 31,
          "recno": 12547283,
          "documentId": 753800,
          "documentNumber": 20042799,
          "documentDate": "2017-04-22T00:00:00",
          "warehouseId": 12
        },
        "quantityAfter": -21,
        "shortageQuantity": 21,
        "movementPosition": 27,
        "movementCount": 184,
        "currentState": {
          "physicalQuantity": 4,
          "availableQuantity": 3,
          "accountingQuantity": 4,
          "accountingPrice": 800
        }
      }
    }
  ]
}
```

В ответе `GET` поле `accepted` всегда равно `false`: оно имеет смысл только в
непосредственном ответе `POST`, принявшего новую задачу. Поля со значением
`null` не сериализуются. `startedAt` и `completedAt` в status имеют строковый
формат `yyyy-MM-dd'T'HH:mm:ss.SSS`. `operation.documentDate` внутри диагностики
отрицательного остатка также возвращается ISO-строкой, например
`2017-04-22T00:00:00`.

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

## 4. Штатный полный перерасчёт `I_UCHET_TOVAR`

```http
POST /admin/folio/accounting-prices/recalculate/native-full
Content-Type: application/json
```

Этот endpoint соответствует полному перерасчёту учётных цен настольного ФОЛИО.
Он заново вычисляет учётные суммы приходных строк из исходных сумм документов,
налогов и валютных данных, затем пересчитывает расходы и итоги карточки.

На текущем этапе rollback-preview разрешён для `Paint_Rus` и `Paint_Ua`, но
только для склада с точным `SCLAD_R.N_2=1000` и `SCLAD_R.N_4 IS NULL`.
Разрешение preview не включает реальную запись: apply требует двух отдельных
server-side флагов.

### Запрос preview

```json
{
  "warehouseId": 12,
  "previewOnly": true,
  "confirmApply": false
}
```

| Поле | Обязательный | Правило |
|---|---:|---|
| `warehouseId` | да | существующий склад, значение больше нуля |
| `previewOnly` | да | `true` — точный проход с rollback; `false` — preflight и затем apply |
| `confirmApply` | для apply | при `previewOnly=false` должно быть строго `true`; при preview можно не передавать |

`previewOnly=true` — не обычный read-only precheck. Java вызывает точную
`I_UCHET_TOVAR` порциями, читает `art`, `new_art`, `otr_date`, `n_cur`, `n_tot`,
но помечает транзакцию каждой порции на rollback. После ответа данные ФОЛИО не
изменены. Такой проход показывает те же остановки, которые находит штатная
процедура, включая изменения, которые невозможно надёжно предсказать собственной
Java-формулой.

Во время каждой порции процедура реально выполняет DML и удерживает блокировки;
rollback происходит только в конце порции. Поэтому native preview нельзя
запускать во время создания или редактирования документов менеджерами. Это
ограничение относится и к `previewOnly=true`, а не только к apply.

`confirmApply` при preview не используется и может быть `false` или отсутствовать.

### Запрос apply

```json
{
  "warehouseId": 12,
  "previewOnly": false,
  "confirmApply": true
}
```

Для apply `confirmApply=true` обязателен. Перед штатной процедурой Java сначала
проигрывает хронологию движений и находит SKU, которые гарантированно остановят
`I_UCHET_TOVAR`: отрицательный остаток либо нулевой знаменатель средней цены.
Каждая такая проблема сохраняется в `warnings` и в отдельной строке лога, а SKU
временно исключается из расчёта только внутри текущей MSSQL-транзакции. Исходный
тип товара возвращается до завершения транзакции; служебный тип также удаляется.

После этого Java автоматически выполняет полный rollback-preflight:

1. проходит весь склад штатной процедурой;
2. откатывает каждую порцию;
3. пропускает заранее диагностированные проблемные SKU и проверяет весь
   остальной склад;
4. если процедура обнаружила новую, не распознанную предварительной диагностикой
   проблему, возвращает `BLOCKED_NEGATIVE_STOCK`, не фиксируя ни одной порции;
5. успешный preflight запускает второй проход с commit каждой успешной порции,
   по-прежнему пропуская известные проблемные SKU.

Это необходимо потому, что `I_UCHET_TOVAR` возвращает `otr_date` уже после
частичного изменения проблемного SKU. Коммит такой порции опасен. Откатить
порцию и продолжить apply с `new_art` тоже нельзя: тогда будут пропущены
корректные SKU, которые находились в той же откатившейся порции.

Размер порции определяется временем выполнения внутри `I_UCHET_TOVAR`.
Следовательно, второй apply-проход не обязан вернуть те же границы
`art`/`new_art`, что rollback-preflight. Реализация не сравнивает порции по
номерам и не обещает их совпадение. Перед apply Java снимает защищённый
baseline всего склада; внутри каждой apply-транзакции сверяет фактически
обработанный диапазон, а после последней порции повторно сверяет весь baseline.
Отдельно проверяются база, настройки склада, OUT-контракт и поступательное
движение курсора.

POST всегда ставит задачу в фон и при принятии возвращает HTTP `202`. Результат
нужно читать здесь:

```http
GET /admin/folio/accounting-prices/recalculate/native-full/status
```

### Поля статуса

| Поле | Значение |
|---|---|
| `phase` | стадия выполнения: `QUEUED`, `DIAGNOSTIC_SCAN`, `QUARANTINE_PREPARATION`, `PRECHECK_RUNNING`, `PRECHECK_COMPLETED`, `APPLY_RUNNING`, `APPLY_COMPLETED`, `APPLY_STOPPED` или `FAILED` |
| `procedureCalls` | общее число вызовов `I_UCHET_TOVAR`, включая preflight и apply |
| `preflightChunks` | число порций, гарантированно откатившихся во время проверки |
| `committedChunks` | число успешно зафиксированных порций apply |
| `progressUnits` | сумма возвращённых `n_cur` текущего прохода |
| `totalUnits` | `n_tot`, рассчитанный первым вызовом с `art=NULL` |
| `progressPercent` | приблизительный процент текущего прохода |
| `currentArt` | последний товар, о котором сообщила процедура |
| `nextArt` | `new_art`, курсор следующей порции |
| `lastCommittedArt` | последний SKU последней подтверждённой порции |
| `checkpointArt` | входной `art` текущей/оборвавшейся порции; при неизвестном исходе не продолжать автоматически |
| `returnCode` | return status последнего вызова |
| `failedChunk` | сырые OUT-параметры порции, которую Java отклонила до commit: входной, обработанный и следующий артикулы, счётчики, return code и причина проверки |
| `warnings` | пропущенные SKU и неожиданные проблемы процедуры с полной диагностикой |

`progressUnits` на apply начинается заново после preflight. `procedureCalls`
считает оба прохода, но его нельзя сравнивать с `committedChunks` как 2:1:
границы time-based порций между проходами могут отличаться.

### Статусы native job

| Статус | Значение |
|---|---|
| `IDLE` | после старта приложения native job ещё не запускался |
| `BUSY` | общий слот занят точечным, Java-full или native-full расчётом |
| `QUEUED` | задача принята |
| `RUNNING` | выполняется preflight либо apply; смотреть `phase` |
| `PREVIEW_READY` | точный rollback-preview завершён без проблем; БД не изменена |
| `PREVIEW_READY_WITH_WARNINGS` | preview завершён; известные проблемные SKU пропущены и перечислены в `warnings`; БД не изменена |
| `BLOCKED_NEGATIVE_STOCK` | процедура нашла проблему, которую предварительный скан не смог безопасно обойти; apply не начался |
| `COMPLETED` | штатный полный перерасчёт завершён |
| `COMPLETED_WITH_WARNINGS` | все безопасные SKU пересчитаны, проблемные SKU пропущены и перечислены в `warnings` |
| `STOPPED_ON_NEGATIVE_STOCK` | во время apply до первого commit появилась новая проблема; текущая порция откатилась |
| `FAILED` | ошибка до первого commit |
| `FAILED_PARTIAL` | ошибка либо новая отрицательная история после одной или нескольких зафиксированных порций; предыдущие commit сохранены |
| `OUTCOME_UNKNOWN` | приложение не может доказать исход текущей транзакции или обнаружило нарушение её границы; автоматически повторять или продолжать нельзя |

Если `FAILED` вызван нарушением OUT-контракта, ответ содержит отдельный объект:

```json
{
  "status": "FAILED",
  "committedChunks": 0,
  "failedChunk": {
    "inputArt": "ЭКО-ХУД МД 3",
    "outputArt": "ЭКО-ХУД МД 3",
    "nextArt": "ЭКО-ХУД МД 3",
    "returnCode": 0,
    "currentUnits": 40120,
    "totalUnits": 2250174,
    "problemDate": null,
    "resultRowCount": 0,
    "transactionCountBefore": 1,
    "transactionCountAfter": 1,
    "validationError": "I_UCHET_TOVAR returned an invalid continuation cursor"
  }
}
```

`failedChunk` относится именно к отклонённому вызову. Поля верхнего уровня
`currentArt`, `nextArt` и `checkpointArt` могли быть опубликованы после
предыдущей принятой порции и не заменяют эту диагностику.

Пример успешного preview с пропущенным проблемным товаром:

```json
{
  "ok": true,
  "accepted": false,
  "running": false,
  "jobId": "9a4705cb-7190-42af-b96e-fd83e44a2791",
  "status": "PREVIEW_READY_WITH_WARNINGS",
  "phase": "PRECHECK_COMPLETED",
  "database": "Paint_Rus",
  "procedureCalls": 4,
  "preflightChunks": 4,
  "committedChunks": 0,
  "warningCount": 1,
  "warningsTruncated": false,
  "warnings": [
    {
      "code": "NEGATIVE_CHRONOLOGICAL_STOCK",
      "message": "The chronological stock becomes negative; the product will be skipped and other products will continue",
      "details": {
        "sku": "CON-100516109R",
        "warehouseId": 12,
        "initialQuantity": 10,
        "quantityBefore": 10,
        "operation": {
          "kind": "EXPENSE",
          "documentType": "Р",
          "quantity": 31,
          "recno": 12547283,
          "documentId": 753800,
          "documentNumber": 20042799,
          "documentDate": "2017-04-22T00:00:00"
        },
        "quantityAfter": -21,
        "shortageQuantity": 21,
        "movementPosition": 27,
        "movementCount": 184,
        "skipped": true,
        "source": "JAVA_CHRONOLOGY_PREFLIGHT"
      }
    }
  ]
}
```

Если точная процедура всё же возвращает `otr_date` для SKU, отсутствовавшего в
предварительном списке, warning имеет код `FOLIO_NATIVE_RECALCULATION_PROBLEM`
либо `NEGATIVE_CHRONOLOGICAL_STOCK`. Такая неожиданная проблема не обходится:
текущая порция откатывается и job останавливается.

Когда сопоставление возможно, `NEGATIVE_CHRONOLOGICAL_STOCK.details` содержит:

- `initialQuantity` — количество до начала истории;
- `quantityBefore` — расчётный остаток перед первой проблемной операцией;
- `operation` — вид движения, технические и пользовательские номера документа,
  `recno`, дата и количество;
- `quantityAfter` и `shortageQuantity` — состояние сразу после операции и
  величина дефицита;
- `movementPosition` / `movementCount` — позиция в проверенной хронологии;
- `currentState` — текущие физический, свободный и учётный остатки и цена;
- `procedureArt`, `folioProblemDate`, `checkpointArt`, `nextArt` — сигналы и
  курсоры самой `I_UCHET_TOVAR`.

Это диагностика причины отрицания, а не только текущего `KON_KOLCH`.

Предварительный native-скан может вернуть три кода безопасного пропуска:

| Код | Причина |
|---|---|
| `NEGATIVE_CHRONOLOGICAL_STOCK` | расход делает хронологический остаток отрицательным |
| `ZERO_ACCOUNTING_QUANTITY_DENOMINATOR` | приход приводит знаменатель средней цены к нулю и штатная процедура упала бы с divide by zero |
| `AMBIGUOUS_MOVEMENT_ORDER` | несколько движений имеют одинаковый legacy sort key; точный порядок SQL Server не доказан |
У всех трёх `details.skipped=true`: этот SKU не изменяется, но обработка других
товаров продолжается.

Пустая новая карточка без движений, с нулевыми начальным остатком и учётной
ценой, но уже заданной продажной ценой, является нормальным состоянием до
первого прихода. По этому признаку API не пропускает товар и не возвращает
warning. Проверка Paint_Ua, исключившая 9 732 такие карточки, всё равно получила
тот же `Divide by zero` на другом checkpoint; эта причина была опровергнута.

### Ограничения первого релиза

- разрешён только экспериментально подтверждённый точный код средней цены
  `SCLAD_R.N_2=1000` (`calculationMode=0`, `periodMode=0`, без налога);
- `SCLAD_R.N_4` должен быть `NULL`; складские группы пока блокируются;
- FIFO/LIFO и партии блокируются до отдельных golden-master тестов;
- параметры метода, периода и учёта налога берутся только из `SCLAD_R.N_2`, а
  не из запроса;
- native status хранится в памяти. После рестарта он станет `IDLE`, но уже
  зафиксированные MSSQL-порции не откатятся. Логи фиксируют только commit,
  успешно наблюдённые приложением, и **не являются транзакционным checkpoint**:
  процесс может завершиться между MSSQL commit и записью строки лога;
- rollback-preview разрешён для `Paint_Rus` и `Paint_Ua`; реальный apply в
  рабочей базе требует резервной копии и согласованного контрольного окна.

### Минимальные права MSSQL

Помимо чтения `SCL_MOVE`, `SCL_ARTC`, `SCLAD_R` и выполнения
`dbo.I_UCHET_TOVAR`, native-full теперь требует строго ограниченные права:

- `UPDATE` на `dbo.SCL_ARTC` — временно назначить и вернуть тип только
  диагностированным SKU;
- `SELECT`, `INSERT`, `DELETE` на `dbo.TIP_TOVR` — создать и удалить одну
  служебную строку внутри той же транзакции.

`db_owner` для этого не нужен. Если этих прав нет, job завершится `FAILED` до
первого commit. Не выдавайте широкие права на всю базу.

## Постоянный журнал пропущенных товаров

Точечный и Java-full маршруты записывают каждый обнаруженный
`NEGATIVE_CHRONOLOGICAL_STOCK` отдельной структурированной строкой в
`${LOG_DIR}/folio-accounting-price.log` с неизменным именем события:

```text
[folio.accounting-price] accounting_price_negative_stock sku=... warehouse=... recno=... documentId=... documentNumber=... date=... initialQuantity=... quantityBefore=... operationType=... operationQuantity=... quantityAfter=... shortageQuantity=... movementPosition=... movementCount=... currentPhysical=... currentAvailable=... currentAccountingQuantity=... currentAccountingPrice=...
```

Запись создаётся и при preview, и при apply, потому что фиксирует результат
диагностики, а не факт изменения БД. Повторный запуск проверки создаст новую
строку. Файл ротируется текущей конфигурацией приложения: хранение 30 дней,
максимум 20 MB на часть и 1 GB суммарно.

`max-reported-warnings` может усечь массив `warnings` в HTTP status, но не
останавливает запись обнаруженных отрицательных остатков в
`folio-accounting-price.log`. Native-full для каждого автоматически пропущенного SKU
native-проход пишет событие `native_sku_skipped` с `job`, складом, SKU, кодом
причины и хронологической диагностикой, включая документ и количество
до/после операции. Неожиданная остановка
процедуры дополнительно пишет
`native_negative_stock` с `job`, складом и курсорами процедуры. Сам status
фоновой задачи хранится только в памяти Java и после рестарта недоступен;
файл лога остаётся на диске согласно политике ротации.

```text
[folio.accounting-price] native_sku_skipped job=... warehouse=... sku=... code=... recno=... documentId=... documentNumber=... date=... initialQuantity=... quantityBefore=... operationQuantity=... quantityAfter=... movementPosition=... movementCount=... currentPhysical=... currentAvailable=... currentAccountingQuantity=... currentAccountingPrice=...
```

Лог предназначен для диагностики, а не для возобновления job. Он не состоит в
одной транзакции с MSSQL: наличие строки подтверждает наблюдение приложения, но
отсутствие строки не доказывает отсутствие commit. После `FAILED_PARTIAL` или
`OUTCOME_UNKNOWN` запрещено автоматически продолжать с `lastCommittedArt` или
`checkpointArt`.

## Как это соотносится со штатным интерфейсом ФОЛИО

SQL-процедура не умеет показывать диалог и сама не задаёт оператору вопрос.
При отрицательной истории она возвращает вызывающему коду проблемный товар,
дату и позицию продолжения. Уже настольный клиент ФОЛИО показывает сообщение и
решает, запускать ли следующий фрагмент.

Документация и разбор procedures подтверждают сигнал об отрицательном остатке,
но не подтверждают точный текст и набор кнопок каждой версии настольного
клиента. Поэтому два Java-режима решают эту ситуацию явно и по-разному:

- `/recalculate/full` сам получает список SKU, проверяет каждый товар и вызывает
  `dbo.i_uchet_add(mode=2)`. Только этот запрос имеет
  `continueOnNegativeStock`: проблемный SKU можно пропустить;
- `/recalculate/native-full` вызывает `I_UCHET_TOVAR` и не принимает
  `continueOnNegativeStock`: безопасный пропуск известных проблем выполняется
  автоматически после диагностического скана. Preview продолжает в
  rollback-транзакциях. Только неожиданная проблема самой процедуры блокирует
  apply; новая проблема во втором проходе откатывает текущую порцию и
  останавливает job.

Таким образом `/full` — безопасный Java-обход из уже сохранённых учётных сумм,
а `/native-full` — полный штатный пересчёт исходных приходных сумм. Эти режимы
не взаимозаменяемы.

## Коды HTTP

| HTTP | Код | Причина |
|---:|---|---|
| 400 | `VALIDATION_ERROR` | отсутствует поле или неверен формат |
| 400 | `NATIVE_FULL_CONFIRMATION_REQUIRED` | для `previewOnly=false` не передано `confirmApply=true` |
| 403 | `ACCOUNTING_PRICE_API_DISABLED` | весь модуль, включая preview POST, выключен до подтверждения защиты `/admin` |
| 403 | `ACCOUNTING_PRICE_APPLY_DISABLED` | точечный apply выключен конфигурацией |
| 403 | `ACCOUNTING_PRICE_FULL_APPLY_DISABLED` | полный apply выключен конфигурацией |
| 403 | `ACCOUNTING_PRICE_NATIVE_FULL_DISABLED` | native-full, включая его rollback-preview, выключен отдельным флагом |
| 403 | `ACCOUNTING_PRICE_NATIVE_FULL_APPLY_DISABLED` | native apply не разрешён одновременно общим и отдельным apply-флагами |
| 403 | `ACCOUNTING_PRICE_NATIVE_DATABASE_NOT_ALLOWED` | текущая MSSQL-база отсутствует в native allow-list |
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

1. status и preview доступны по защищённому VPN/internal каналу; при аварийной
   необходимости их можно выключить через `api-enabled=false`;
2. оставить оба apply-флага выключенными до резервной копии и согласованного
   окна;
3. выполнить точечный `previewOnly=true` на проверенных товарах;
4. выполнить полный `previewOnly=true`, разобрать все пропуски;
5. включить точечное применение и сверить один товар с интерфейсом ФОЛИО;
6. для native сначала выполнить rollback-preview; он доступен и в `Paint_Rus`,
   и в `Paint_Ua`, но может создавать нагрузку и блокировки;
7. сделать резервную копию и только затем отдельно включить нужный full apply;
8. первый apply любого полного режима запускать в окно без ручного
   перерасчёта.

Используются следующие настройки:

```properties
lavka.folio.accounting-prices.api-enabled=${LAVKA_FOLIO_ACCOUNTING_PRICE_API_ENABLED:true}
lavka.folio.accounting-prices.apply-enabled=${LAVKA_FOLIO_ACCOUNTING_PRICE_APPLY_ENABLED:false}
lavka.folio.accounting-prices.full-apply-enabled=${LAVKA_FOLIO_ACCOUNTING_PRICE_FULL_APPLY_ENABLED:false}
lavka.folio.accounting-prices.native-full-enabled=${LAVKA_FOLIO_ACCOUNTING_PRICE_NATIVE_FULL_ENABLED:true}
lavka.folio.accounting-prices.native-full-apply-enabled=${LAVKA_FOLIO_ACCOUNTING_PRICE_NATIVE_FULL_APPLY_ENABLED:false}
lavka.folio.accounting-prices.native-full-allowed-databases=${LAVKA_FOLIO_ACCOUNTING_PRICE_NATIVE_FULL_ALLOWED_DATABASES:Paint_Rus,Paint_Ua}
lavka.folio.accounting-prices.native-full-max-chunks=${LAVKA_FOLIO_ACCOUNTING_PRICE_NATIVE_FULL_MAX_CHUNKS:10000}
lavka.folio.accounting-prices.lock-timeout-ms=${LAVKA_FOLIO_ACCOUNTING_PRICE_LOCK_TIMEOUT_MS:5000}
lavka.folio.accounting-prices.query-timeout-seconds=${LAVKA_FOLIO_ACCOUNTING_PRICE_QUERY_TIMEOUT_SECONDS:120}
lavka.folio.accounting-prices.native-full-timeout-seconds=${LAVKA_FOLIO_ACCOUNTING_PRICE_NATIVE_FULL_TIMEOUT_SECONDS:900}
lavka.folio.accounting-prices.max-reported-warnings=${LAVKA_FOLIO_ACCOUNTING_PRICE_MAX_REPORTED_WARNINGS:200}
lavka.folio.accounting-prices.zone=${LAVKA_FOLIO_ACCOUNTING_PRICE_ZONE:Europe/Kyiv}
```

`api-enabled` открывает все endpoint модуля, включая preview и status. В текущей
архитектуре доступ ограничен VPN/internal network, поэтому значение по умолчанию
`true`; флаг остаётся аварийным выключателем.

`apply-enabled` разрешает точечный apply. Для полного apply должны одновременно
быть `true` оба флага: `apply-enabled` и `full-apply-enabled`. Full apply намеренно
остаётся выключенным по умолчанию даже после включения preview и точечного
режима. Feature flags являются защитой от случайного запуска и не заменяют
авторизацию.

Native-full имеет отдельную лестницу защиты:

- его rollback-preview требует одновременно `api-enabled=true` и
  `native-full-enabled=true`;
- его apply требует ещё `apply-enabled=true` и
  `native-full-apply-enabled=true`;
- `native-full-allowed-databases` проверяется и до запуска, и на соединении
  внутри каждой MSSQL-транзакции. Стандартное значение — `Paint_Rus,Paint_Ua`;
- `native-full-max-chunks` аварийно останавливает проход, если legacy-курсор не
  завершился за заданное число вызовов. Это safety limit, а не размер порции.

`query-timeout-seconds` ограничивает обычные read/write-транзакции, точечный
перерасчёт и Java-full. `native-full-timeout-seconds` отдельно ограничивает
диагностический запрос и одну транзакционную time-based порцию штатного
`I_UCHET_TOVAR`, а не весь фоновый проход. Стандартное значение native-full —
900 секунд, чтобы большой склад не наследовал короткий лимит точечного API.
Тайм-аут приводит к rollback текущей транзакции, однако при потере связи исход
commit нельзя угадывать: status может потребовать ручной разбор как
`OUTCOME_UNKNOWN`.

Известные проблемные SKU временно исключаются пакетно: одна выборка и одно
обновление на группу до 400 товаров, затем одно CASE-обновление восстанавливает
их индивидуальные `TIP_TOVR`. Все эти действия остаются внутри той же MSSQL-
транзакции, что и вызов процедуры. Фаза `QUARANTINE_PREPARATION` означает именно
эту подготовку; это не зависание и ещё не подтверждённый commit.

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
- Нельзя полагаться только на отсутствие SQL exception. Точечный и Java-full
  режимы проверяют `TMP_MOVE`, остатки, структуру движений и денежные
  postconditions. Native-full проверяет OUT-контракт, направление курсора,
  счётчики прогресса, транзакционную границу, неизменность настроек склада,
  защищённый baseline всего склада, фактически обработанный диапазон каждой
  порции и финальное состояние всего склада.
- Оба full apply частично фиксируемые, но единица commit различается:
  `/full` фиксирует один SKU, `/native-full` — одну time-based порцию
  `I_UCHET_TOVAR`. Атомарного rollback всего склада после первого commit нет.
- Состояние фоновой задачи хранится в памяти процесса Java. После рестарта
  status снова будет `IDLE`; незавершённая задача автоматически не
  возобновляется. Уже зафиксированные транзакции сохраняются. Лог нельзя
  использовать как точный реестр commit.
- Preview и apply native-full являются двумя отдельными проходами. Из-за
  ограничения legacy-процедуры по времени их порции не обязаны иметь одинаковые
  границы; безопасность обеспечивается повторными проверками, а не сравнением
  номера порции.
- Поддержка учётных групп, LIFO, FIFO, фиксированной цены и партий требует
  отдельных golden-master экспериментов на `Paint_Rus` и не включается
  автоматически.

## Источники подтверждения

- определения штатных procedures текущей версии ФОЛИО;
- руководство ФОЛИО о предупреждении при отрицательном остатке;
- воспроизводимый rollback-эксперимент на копии `Paint_Rus` для точечного
  штатного route;
- rollback golden-master полного `I_UCHET_TOVAR` на `Paint_Rus`: чистое
  завершение, восстановление приходных `SUM_UCHET/SUM_UCVAL` и поведение при
  отрицательной хронологии;
- текущая схема `SCL_ARTC`, `SCL_MOVE`, `SCLAD_R` и поведение `TMP_MOVE`.

Обезличенный протокол native-эксперимента:
[12_accounting_price_native_full_golden_master.md](../folio-experiments/12_accounting_price_native_full_golden_master.md).

Ни один пример этого документа не является разрешением запускать apply в
рабочей `Paint_Ua` без backup, включённого feature flag и операционного окна.
