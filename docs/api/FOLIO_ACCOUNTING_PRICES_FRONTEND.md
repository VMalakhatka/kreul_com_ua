# Перерасчёт учётных цен ФОЛИО: контракт для фронта

Полная инструкция для фронта, оператора и ночного cron:
[FOLIO_ACCOUNTING_PRICE_OPERATIONS_FRONTEND.md](FOLIO_ACCOUNTING_PRICE_OPERATIONS_FRONTEND.md).

## Главное различие двух полных режимов

В API есть два разных фоновых прохода. Их нельзя показывать как один и тот же
расчёт.

| Режим | Endpoint | Штатная процедура | Что делает |
|---|---|---|---|
| Java-full | `POST /admin/folio/accounting-prices/recalculate/full` | `i_uchet_add(mode=2)` по одному SKU | восстанавливает итоги карточки из уже сохранённых `SUM_UCHET/SUM_UCVAL` |
| Native-full | `POST /admin/folio/accounting-prices/recalculate/native-full` | `LAVKA_I_UCHET_TOVAR_SAFE`, производная от `I_UCHET_TOVAR` | выполняет полный алгоритм ФОЛИО по одному SKU и диагностирует опасное деление до SQL-ошибки |

Java-full умеет пропускать проблемный SKU через `continueOnNegativeStock`.
Native-full такого параметра **не имеет**: он всегда откатывает проблемный SKU,
записывает warning и продолжает остальные товары.

Native-full разрешён для проверенной средней цены `SCLAD_R.N_2=1000`
(`includeTax=false`) и `1100` (`includeTax=true`), при `SCLAD_R.N_4 IS NULL`.
Для `1100` в целевой базе должна быть установлена обновлённая
`LAVKA_I_UCHET_*_SAFE`; одной новой версии Java недостаточно. Rollback-preview
доступен в `Paint_Rus` и `Paint_Ua`; apply управляется отдельными флагами.

## Что такое учётная цена

Учётная цена — внутренняя себестоимость единицы товара. Это не розничная и не
отпускная цена. ФОЛИО использует её для оценки остатка и стоимости расхода.

Для средней цены учитываются начальное количество и стоимость, приходы,
расходы и итоговая учётная сумма. Java контролирует, чтобы перерасчёт не менял
физический `KON_KOLCH`, свободный `REZ_KOLCH`, структуру документов и другие
защищённые исходные данные.

## Рекомендуемый интерфейс

Разделите экран на три действия:

1. «Проверить/пересчитать один товар»;
2. «Обход товаров из сохранённых учётных сумм» — Java-full;
3. «Полный штатный пересчёт ФОЛИО» — native-full, с отдельным предупреждением и
   подтверждением apply.

Для фоновых режимов POST отправляется ровно один раз. После HTTP `202` фронт
сохраняет `jobId` и опрашивает соответствующий GET status раз в 2–5 секунд.
Повторный POST не является polling.

После успешного apply Java связывает подтверждённое состояние с техническим
снимком товара. Warning `SNAPSHOT_CONFIRMATION_NOT_RECORDED` означает: сам
перерасчёт ФОЛІО уже зафиксирован, но MariaDB не сохранила подтверждающий
fingerprint. Такой warning нельзя превращать в автоматический повтор apply;
нужно обновить snapshot и проверить состояние SKU.

## Один товар

```http
POST /admin/folio/accounting-prices/recalculate
Content-Type: application/json
```

```json
{
  "sku": "ЕВ-МАСЛ-РАДИАТОР",
  "warehouseId": 12,
  "previewOnly": true
}
```

Все поля обязательны. Сначала всегда запускать `previewOnly=true`. Такой preview
только анализирует данные и не вызывает изменяющую процедуру.

## Java-full: обход всех SKU

### Запуск

```http
POST /admin/folio/accounting-prices/recalculate/full
Content-Type: application/json
```

```json
{
  "warehouseId": 12,
  "previewOnly": true,
  "continueOnNegativeStock": true
}
```

Для apply меняется только `previewOnly`:

```json
{
  "warehouseId": 12,
  "previewOnly": false,
  "continueOnNegativeStock": true
}
```

`continueOnNegativeStock=true` означает: не изменять проблемный SKU, записать
warning и перейти к следующему. `false` останавливает Java-full на первом
`NEGATIVE_CHRONOLOGICAL_STOCK`. Этот параметр относится только к Java-full.

Статус:

```http
GET /admin/folio/accounting-prices/recalculate/full/status
```

Основные статусы: `IDLE`, `BUSY`, `QUEUED`, `RUNNING`, `COMPLETED`,
`COMPLETED_WITH_WARNINGS`, `STOPPED_ON_NEGATIVE_STOCK`, `FAILED`,
`FAILED_PARTIAL`.

## Native-full: полный `I_UCHET_TOVAR`

### Точный rollback-preview

```http
POST /admin/folio/accounting-prices/recalculate/native-full
Content-Type: application/json
```

```json
{
  "warehouseId": 12,
  "previewOnly": true,
  "confirmApply": false
}
```

Это не обычный read-only анализ. Java действительно вызывает безопасную копию
алгоритма `I_UCHET_TOVAR`, но каждый SKU выполняет в отдельной транзакции с
обязательным rollback. Поэтому preview видит штатные OUT-сигналы
`art/new_art/otr_date/n_cur/n_tot`, а постоянные данные ФОЛИО после него не
изменяются.

Перед запуском фронт должен показать обязательное предупреждение: во время
native preview менеджеры не должны создавать, сохранять или исправлять документы
в настольной ФОЛИО. Preview не оставляет постоянных изменений, но временно
выполняет DML и держит блокировки до rollback текущего SKU.

При preview `confirmApply` можно не передавать. Параметра
`continueOnNegativeStock` в native request нет.

### Реальный apply

```json
{
  "warehouseId": 12,
  "previewOnly": false,
  "confirmApply": true
}
```

`confirmApply=true` обязателен. Для полного `native-full` Java выполняет два
прохода:

1. rollback-preflight проверяет каждый SKU отдельным вызовом safe-процедуры;
2. отрицательный остаток или нулевой знаменатель возвращается как подробный
   warning, а транзакция этого SKU откатывается;
3. apply повторяет склад в том же порядке;
4. каждый чистый SKU фиксируется отдельным commit;
5. каждый проблемный SKU снова откатывается, не дублируется в warnings и не
   останавливает обработку следующих товаров.

Наличие таких warnings не блокирует apply. Успешный preview с пропусками имеет
`PREVIEW_READY_WITH_WARNINGS`, а успешный apply —
`COMPLETED_WITH_WARNINGS`. Проблемные товары остаются без перерасчёта, остальные
товары склада пересчитываются. Менеджер может исправить документы из warnings и
запустить расчёт ещё раз.

Новая проблема, появившаяся между preflight и apply, также откатывается только
для своего SKU и добавляется в warnings. `FAILED`/`FAILED_PARTIAL` остаются для
неожиданных SQL, connection, contract или protected-data ошибок, а не для
диагностированного отрицательного остатка.

### Native-range: один SKU, диапазон или явный список

После исправления проблемных документов не нужно повторять весь склад:

```http
POST /admin/folio/accounting-prices/recalculate/native-range
Content-Type: application/json
```

Один SKU или непрерывный диапазон:

```json
{
  "warehouseId": 5,
  "fromSku": "KR-84127",
  "toSku": "KR-84127",
  "previewOnly": false,
  "confirmApply": true
}
```

```json
{
  "warehouseId": 5,
  "fromSku": "KR-84127",
  "toSku": "KR-84999",
  "previewOnly": false,
  "confirmApply": true
}
```

Несмежные исправленные товары лучше передавать явно:

```json
{
  "warehouseId": 5,
  "skus": ["KR-84127", "СТИ-741449R", "ТП-0001"],
  "previewOnly": false,
  "confirmApply": true
}
```

Передаётся либо `skus[]`, либо одновременно `fromSku` и `toSku`; смешивать
режимы нельзя. Максимум — 500 SKU. Артикулы проверяются и упорядочиваются самой
ФОЛІО. Если SKU из явного `skus[]` уже исчез из `SCL_ARTC`, Java безопасно
пропускает его без вызова процедуры и продолжает остальные товары.

Preview использует `previewOnly=true`, `confirmApply=false`. Apply сначала
проверяет все выбранные SKU с rollback, затем повторяет только этот же набор и
фиксирует каждый чистый SKU отдельным commit. Диагностируемый проблемный SKU
откатывается, получает warning/`FAILED`, а остальные продолжаются.

Для автоматической SKU-кампании рекомендуется однопроходный safe-режим:

```json
{
  "warehouseId": 5,
  "skus": ["KR-84127", "СТИ-741449R", "ТП-0001"],
  "previewOnly": false,
  "confirmApply": true,
  "applyMode": "SAFE_APPLY_ONLY"
}
```

Он разрешён только для `native-range`. Каждый SKU выполняется в собственной
транзакции. Код `20` с обязательной диагностикой откатывает текущий SKU,
записывает warning/`FAILED` и позволяет перейти к следующему. Неизвестная
ошибка откатывает текущий SKU и останавливает кампанию; ранее зафиксированные
SKU остаются `VERIFIED`. Во время планового рестарта MSSQL Java может временно
вернуть `status=RUNNING`, `phase=WAITING_FOR_FOLIO_RESTART`; это ожидание, а не
финальная ошибка. `OUTCOME_UNKNOWN` после исчерпания безопасного восстановления
по-прежнему запрещает автоматический повтор.
Для ручного preview поле `applyMode` не передавать.

Оптимизация backend не меняет JSON или UI-алгоритм: для `SAFE_APPLY_ONLY`
защитный baseline читается только для SKU текущего пакета, а fingerprints
успешных commit публикуются пакетно. Фронт так же опрашивает status и не должен
отправлять следующий пакет, пока текущий не завершился.

Нужно добавить одно отображение существующего `warnings[]`:

- `code=SELECTED_PRODUCT_NO_LONGER_EXISTS` — показать «Товар видалено або
  перейменовано у ФОЛІО; перерахунок для нього не виконувався»;
- использовать `details.sku`, `warehouseId` и `stage` в техническом блоке;
- явно показать `procedureExecuted=false` и `committed=false`;
- рекомендация оператору: обновить snapshot, после чего товар должен перейти
  в `REMOVED`;
- не относить это предупреждение к отрицательному остатку, не требовать
  исправления документа и не останавливать следующий пакет.

Пример элемента `warnings[]`:

```json
{
  "code": "SELECTED_PRODUCT_NO_LONGER_EXISTS",
  "message": "The selected product no longer exists in the Folio warehouse; it was skipped and remaining products will continue",
  "details": {
    "sku": "ЯЯАМВУ-MFМ-263/1",
    "warehouseId": 9,
    "stage": "PROTECTED_BASELINE_CAPTURE",
    "source": "NATIVE_RANGE_SELECTION",
    "skipped": true,
    "procedureExecuted": false,
    "committed": false,
    "recommendation": "Refresh the product snapshot; the missing product should become REMOVED before the next campaign"
  }
}
```

Запросы, polling и терминальные статусы менять не нужно. Если это единственная
проблема, job завершается `COMPLETED_WITH_WARNINGS`, а отсутствующий SKU уже
включён в `processedSku/progressUnits`.

Статус можно читать по адресу:

```http
GET /admin/folio/accounting-prices/recalculate/native-range/status
```

Он использует тот же общий слот и тот же формат ответа, что native-full.
Одновременно point/full/native-full/native-range не выполняются.

Для `native-range` счётчики имеют отдельный формальный смысл:

- `processedSku` и `progressUnits` — сколько выбранных Java SKU уже завершено;
- `totalUnits` — каноническое количество SKU выбранной кампании, заранее
  полученное Java из ФОЛІО;
- `currentUnits` / `procedureCurrentUnits` — вес работы последнего вызова
  safe-процедуры (например `40` за карточку плюс движения);
- `procedureTotalUnits` — сырой `n_tot` safe-процедуры и только диагностика.

`procedureTotalUnits=0` для `native-range` является допустимым: процедура
обрабатывает один SKU и не обязана знать размер WordPress-кампании. Фронт строит
индикатор выполнения по `progressUnits/totalUnits` или
`processedSku/totalUnits`, но не по `procedureTotalUnits`.

Пример после успешной обработки первого SKU кампании из 118 товаров:

```json
{
  "status": "RUNNING",
  "phase": "APPLY_RUNNING",
  "currentArt": "A-AZ БАНК.РАСХ",
  "nextArt": "A-AZ Краски б/у",
  "processedSku": 1,
  "progressUnits": 1,
  "totalUnits": 118,
  "currentUnits": 40,
  "procedureCurrentUnits": 40,
  "procedureTotalUnits": 0,
  "committedChunks": 1
}
```

### Ответ на принятый POST

HTTP `202 Accepted`:

```json
{
  "ok": true,
  "accepted": true,
  "running": true,
  "jobId": "9a4705cb-7190-42af-b96e-fd83e44a2791",
  "status": "QUEUED",
  "phase": "QUEUED",
  "request": {
    "warehouseId": 12,
    "previewOnly": true,
    "confirmApply": false
  },
  "startedAt": "2026-08-15T18:10:03.145",
  "database": "Paint_Rus",
  "procedureCalls": 0,
  "preflightChunks": 0,
  "committedChunks": 0,
  "progressUnits": 0,
  "totalUnits": 0,
  "warningCount": 0,
  "warningsTruncated": false,
  "warnings": []
}
```

Если общий слот перерасчёта занят, POST возвращает HTTP `409`,
`accepted=false`, `status=BUSY` либо текущий выполняющийся native status.
Автоматически повторять POST нельзя.

### Polling

```http
GET /admin/folio/accounting-prices/recalculate/native-full/status
```

В ответе GET `accepted=false` — это нормально: поле означает только факт приёма
конкретного POST. Polling продолжается, пока `running=true`.

Пример процесса preflight:

```json
{
  "ok": true,
  "accepted": false,
  "running": true,
  "jobId": "9a4705cb-7190-42af-b96e-fd83e44a2791",
  "status": "RUNNING",
  "phase": "PRECHECK_RUNNING",
  "request": {
    "warehouseId": 12,
    "previewOnly": false,
    "confirmApply": true
  },
  "database": "Paint_Rus",
  "accountingMethod": {
    "rawCode": 1000,
    "calculationMode": 0,
    "periodMode": 0,
    "includeTax": false,
    "name": "AVERAGE"
  },
  "procedureCalls": 3,
  "preflightChunks": 3,
  "committedChunks": 0,
  "progressUnits": 18300,
  "totalUnits": 36738,
  "progressPercent": 49,
  "currentArt": "KR-16234",
  "nextArt": "KR-16235",
  "checkpointArt": "KR-16000",
  "returnCode": 0,
  "warningCount": 0,
  "warningsTruncated": false,
  "warnings": []
}
```

Во время `APPLY_RUNNING` `progressUnits` начинается заново с нуля.

Финальный чистый preview выглядит так:

```json
{
  "ok": true,
  "accepted": false,
  "running": false,
  "jobId": "9a4705cb-7190-42af-b96e-fd83e44a2791",
  "status": "PREVIEW_READY",
  "phase": "PRECHECK_COMPLETED",
  "request": {
    "warehouseId": 12,
    "previewOnly": true,
    "confirmApply": false
  },
  "startedAt": "2026-08-15T18:10:03.145",
  "completedAt": "2026-08-15T18:12:41.073",
  "database": "Paint_Rus",
  "accountingMethod": {
    "rawCode": 1000,
    "calculationMode": 0,
    "periodMode": 0,
    "includeTax": false,
    "name": "AVERAGE"
  },
  "procedureCalls": 6,
  "preflightChunks": 6,
  "committedChunks": 0,
  "progressUnits": 36738,
  "totalUnits": 36738,
  "progressPercent": 100,
  "returnCode": 0,
  "warningCount": 0,
  "warningsTruncated": false,
  "warnings": []
}
```

`committedChunks=0` здесь обязательно: все native-preview SKU откатились.

### Поля native status

| Поле | Как отображать |
|---|---|
| `status` | итоговое состояние job |
| `phase` | текущая стадия: `QUEUED`, `PRECHECK_RUNNING`, `PRECHECK_COMPLETED`, `APPLY_RUNNING`, `WAITING_FOR_FOLIO_RESTART`, `APPLY_RECOVERY`, `APPLY_COMPLETED` или `FAILED` |
| `procedureCalls` | число вызовов `LAVKA_I_UCHET_TOVAR_SAFE` в обоих проходах |
| `preflightChunks` | число гарантированно откатившихся SKU проверки |
| `committedChunks` | число чистых SKU, подтверждённо зафиксированных apply |
| `progressUnits` / `totalUnits` | для native-full — legacy work units; для native-range — обработанные/выбранные SKU |
| `processedSku` | число завершённых SKU native-range, включая безопасно пропущенные отсутствующие карточки; для native-full отсутствует |
| `currentUnits` / `procedureCurrentUnits` | сырой `n_cur` последнего вызова safe-процедуры |
| `procedureTotalUnits` | сырой `n_tot`; для native-range может корректно быть `0` и не является знаменателем прогресса кампании |
| `progressPercent` | приблизительный процент; может отсутствовать до получения `n_tot` |
| `currentArt` / `nextArt` | текущий SKU и следующий SKU в порядке ФОЛИО |
| `lastCommittedArt` | последний подтверждённо зафиксированный SKU |
| `checkpointArt` | входной SKU текущей или оборвавшейся транзакции |
| `returnCode` | return status последнего вызова |
| `failedChunk` | фактические входные и OUT-значения SKU, отклонённого проверкой Java |
| `warningCount` | полное число найденных проблем |
| `warningsTruncated` | `true`, если массив `warnings` показан не полностью |
| `error` | техническая причина финальной ошибки |

`lastCommittedArt` и `checkpointArt` показываются только для диагностики. Они не
являются разрешением автоматически продолжить job.

При `FAILED` и непустом `failedChunk` показывайте отдельный технический блок:
`inputArt`, `outputArt`, `nextArt`, `returnCode`, `currentUnits`, `totalUnits`,
`procedureCurrentUnits`, `procedureTotalUnits`, `problemDate` и
`validationError`. Для native-range `totalUnits` — известный Java размер
выборки, а `procedureTotalUnits` — сырой OUT процедуры. Не подменяйте этот блок
значениями верхнего уровня:
они могут относиться к предыдущему успешно принятому SKU. Кнопку
автоматического повтора не показывать; при `committedChunks=0` данные откатились,
но сначала нужно разобрать сырой OUT-контракт.

Если Java действительно не смогла определить размер выбранной кампании, job
завершается с `errorCode=NATIVE_RANGE_TOTAL_UNKNOWN`. Если нарушен другой
контракт safe-процедуры, используется
`errorCode=NATIVE_RANGE_CONTRACT_INVALID`. Нулевой
`procedureTotalUnits` при известном ненулевом `totalUnits` сам по себе ни одним
из этих состояний не является.

`NATIVE_SAFE_PROCEDURE_UNSUPPORTED_SCOPE_OR_MODE` соответствует return code
`32`. Это fail-stop несовместимой или устаревшей safe-процедуры, а не
пропущенный товар. Для склада с `SCLAD_R.N_2=1100` наиболее вероятна ранняя
версия, запрещающая `uch_nal=1`. Показывайте «Зупинено: несумісна версія
процедури ФОЛІО», `status=FAILED`, склад в списке ошибок и запрет
автоматического retry. Не показывайте «Завершено з пропущеними товарами» и не
меняйте SKU на обычный `FAILED` до обновления процедуры и успешного preview.

`NATIVE_SAFE_PROCEDURE_TRANSACTION_REQUIRED` соответствует коду `31`. Любой
другой код, кроме `0`, `20`, `31`, `32`, возвращается как
`NATIVE_SAFE_PROCEDURE_UNDOCUMENTED_CODE`; курсор автоматически не
продолжается.

Не сравнивайте артикулы лексикографически в PHP/JavaScript и не определяйте по
ним движение курсора. Порядок ФОЛИО задаётся legacy CP1251-collation колонки
`SCL_ARTC.COD_ARTIC`; корректность курсора проверяет только Java API через эту
колонку. Java приводит обработанный артикул к исходному `varchar(20)` и проверяет,
что `nextArt` совпадает с фактическим `MIN(COD_ARTIC)` после `outputArt`. Это важно
для артикулов с пробелами, дефисами и кириллицей: обычное сравнение Unicode-строк
может не совпасть с порядком ФОЛИО. Один вызов относится ровно к одному
`outputArt`.

Warning `ZERO_ACCOUNTING_DENOMINATOR` означает, что safe-процедура остановила
SKU до деления на ноль. Показывайте `sku`, `recno`, `operationDate`, `formula`,
`numerator`, `denominator`, `quantityBefore` и `movementQuantity`. Он не
блокирует успешный итог `PREVIEW_READY_WITH_WARNINGS` или
`COMPLETED_WITH_WARNINGS`.

### Native статусы

| Статус | Отображение и действие |
|---|---|
| `IDLE` | native job после рестарта ещё не запускался |
| `BUSY` | другой перерасчёт занял общий слот; не повторять автоматически |
| `QUEUED` | запрос принят |
| `RUNNING` | показать `phase`, прогресс и текущий артикул |
| `PREVIEW_READY` | точный preview завершён, данные откатились, проблем нет |
| `PREVIEW_READY_WITH_WARNINGS` | preview завершён; показать пропущенные SKU и разрешить подтверждённый apply |
| `COMPLETED` | apply завершён |
| `COMPLETED_WITH_WARNINGS` | безопасные SKU пересчитаны; показать пропущенные SKU |
| `FAILED` | ошибка до первого commit |
| `FAILED_PARTIAL` | предыдущие SKU уже могли быть зафиксированы; только ручная сверка |
| `OUTCOME_UNKNOWN` | исход текущей транзакции не доказан; запретить автоматический retry и эскалировать оператору |

При `phase=WAITING_FOR_FOLIO_RESTART` фронт должен показывать: «Сервер ФОЛІО
перезавантажується. Java очікує відновлення; не запускайте інший перерахунок».
Продолжать polling того же status; не завершать кампанию, не отправлять следующий
пакет и не вызывать retry. `APPLY_RECOVERY` означает, что связь уже вернулась и
Java доказывает исход последнего commit.

После успешного восстановления возможен итог `COMPLETED_WITH_WARNINGS`:

- `MSSQL_RESTART_COMMIT_CONFIRMED` — commit доказан, повторного apply не было;
- `MSSQL_RESTART_COMMIT_ROLLED_BACK_AND_RETRIED` — commit доказанно не
  сохранился, SKU безопасно применён один раз повторно;
- `MSSQL_RESTART_TRANSACTION_ROLLED_BACK_AND_RETRIED` — соединение оборвалось
  до стадии commit, транзакция откатилась и SKU повторён один раз.

Эти warnings показывать как техническое событие восстановления, а не как
ошибку товара и не переводить SKU в `FAILED`. Если Java-процесс или контейнер
сам был перезапущен, это правило не действует: кампания должна использовать
обычный recovery по snapshot/checkpoint.

`FAILED_PARTIAL` нельзя показывать как обычную ошибку с кнопкой «Повторить».
Сначала оператор должен сверить ФОЛИО и резервную копию. Для
`OUTCOME_UNKNOWN` это требование ещё строже: ни HTTP status, ни лог не дают
достаточного транзакционного checkpoint.

## Диагностика отрицательного остатка

Отрицательный хронологический остаток означает, что при проигрывании истории
расход впервые превысил количество, существовавшее на дату движения. Это не то
же самое, что текущий нулевой остаток.

```json
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
      "documentDate": "2017-04-22T00:00:00",
      "warehouseId": 12
    },
    "quantityAfter": -21,
    "shortageQuantity": 21,
    "skipped": true,
    "source": "JAVA_CHRONOLOGY_PREFLIGHT",
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

Показывайте таблицу:

| SKU | Склад | Документ | Дата | Было | Операция | Стало | Не хватает |
|---|---:|---:|---|---:|---:|---:|---:|
| CON-100516109R | 12 | 20042799 | 22.04.2017 | 10 | расход 31 | -21 | 21 |

Таким же образом выводите `ZERO_ACCOUNTING_QUANTITY_DENOMINATOR` и
`AMBIGUOUS_MOVEMENT_ORDER`. Если
`details.skipped=true`, это не падение всего job: товар пропущен, остальные SKU
продолжают перерасчитываться.

Не показывайте предупреждение только потому, что у новой карточки ещё нет
движений и учётная цена равна нулю, а продажная уже задана. Это нормальное
состояние товара до первого прихода и API больше не помечает его как ошибку.
Не показывайте `checkpointArt` как отдельный виновный SKU: это входной артикул
текущего вызова и поле технической диагностики.

В деталях показывайте `documentId`, `recno`, `movementPosition`,
`currentState`, `procedureArt`, `folioProblemDate` и курсоры.

Если safe-процедура находит нулевой знаменатель, приходит
`ZERO_ACCOUNTING_DENOMINATOR`. Если штатный алгоритм сообщает отрицательный
остаток через `otr_date`, приходит `NEGATIVE_CHRONOLOGICAL_STOCK` с деталями
операции. Оба warning откатывают только этот SKU и не блокируют остальные.

## Логи

Диагностика пишется в:

```text
${LOG_DIR}/folio-accounting-price.log
```

Для каждого пропущенного товара используется событие `native_safe_sku_skipped`.
Файл ротируется 30 дней,
частями до 20 MB и суммарно до 1 GB.

Лог **не является транзакционным checkpoint**. Он пишется отдельно от MSSQL:
процесс может завершиться после commit, но до строки лога. Поэтому фронт не
должен предлагать «продолжить с последнего SKU» на основании файла или status.

`warningsTruncated=true` означает, что HTTP показывает только часть warnings.
Нужно явно предупредить пользователя и предложить выгрузку доступных данных и
просмотр серверного лога.

## HTTP-ошибки

| HTTP | Код/причина | Действие фронта |
|---:|---|---|
| `400` | validation или `NATIVE_FULL_CONFIRMATION_REQUIRED` | показать сообщение; для native apply потребовать явное подтверждение |
| `403` | API либо нужный apply/native flag выключен | показать конфигурационную ошибку оператору |
| `403` | `ACCOUNTING_PRICE_NATIVE_DATABASE_NOT_ALLOWED` | не предлагать обход; текущая база запрещена allow-list |
| `404` | товар или склад не найден | показать конкретный объект |
| `409` | общий слот занят либо применение заблокировано | показать status/warnings; не повторять автоматически |
| `500` | нарушен postcheck/контракт процедуры | текущая транзакция откатилась; показать `reqId` |
| `503` | MSSQL/процедура недоступна или timeout | показать `reqId`; сначала проверить БД и итог job |

Фоновая ошибка после HTTP `202` приходит через status, а не в исходный POST.

## Feature flags

```properties
LAVKA_FOLIO_ACCOUNTING_PRICE_API_ENABLED=true
LAVKA_FOLIO_ACCOUNTING_PRICE_APPLY_ENABLED=false
LAVKA_FOLIO_ACCOUNTING_PRICE_FULL_APPLY_ENABLED=false
LAVKA_FOLIO_ACCOUNTING_PRICE_NATIVE_FULL_ENABLED=true
LAVKA_FOLIO_ACCOUNTING_PRICE_NATIVE_FULL_APPLY_ENABLED=false
LAVKA_FOLIO_ACCOUNTING_PRICE_NATIVE_FULL_ALLOWED_DATABASES=Paint_Rus,Paint_Ua
LAVKA_FOLIO_ACCOUNTING_PRICE_NATIVE_FULL_MAX_CHUNKS=100000
LAVKA_FOLIO_ACCOUNTING_PRICE_NATIVE_FULL_TIMEOUT_SECONDS=900
```

- все endpoint требуют `API_ENABLED=true`;
- Java-full apply требует также `APPLY_ENABLED=true` и
  `FULL_APPLY_ENABLED=true`;
- native rollback-preview требует `NATIVE_FULL_ENABLED=true`;
- native apply требует одновременно `APPLY_ENABLED=true` и
  `NATIVE_FULL_APPLY_ENABLED=true`;
- стандартный native allow-list содержит `Paint_Rus` и `Paint_Ua`;
- отдельный native timeout по умолчанию равен 900 секундам на один SKU, а не
  на весь склад;
- status и preview включены по умолчанию для VPN/internal network;
- оба apply-флага по умолчанию остаются выключенными.

Маршруты вызываются только через защищённый VPN/internal network. Без VPN доступ
к административному `/admin` отсутствует.

## Порядок ввода native-full

1. Оставить apply-флаги выключенными.
2. Выполнить rollback-preview на нужной базе; для `Paint_Ua` запускать его вне
   активной ручной работы ФОЛИО.
3. Запустить rollback-preview и разобрать все warnings.
4. При `PREVIEW_READY_WITH_WARNINGS` показать оператору список пропускаемых
   товаров, но не блокировать кнопку apply.
5. Подтвердить резервную копию и окно без ручного перерасчёта ФОЛИО.
6. После `PREVIEW_READY` или `PREVIEW_READY_WITH_WARNINGS` отдельно включить и
   запустить native apply.
7. После `FAILED_PARTIAL` или `OUTCOME_UNKNOWN` ничего не повторять
   автоматически; выполнить ручную сверку.
