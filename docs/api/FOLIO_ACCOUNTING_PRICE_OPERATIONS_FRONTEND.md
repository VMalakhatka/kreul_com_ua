# Учётные цены ФОЛІО: фронтенд, оператор и ночной cron

## 1. Назначение и источник истины

Экран управляет проверкой и штатным перерасчётом учётных цен ФОЛІО. Он не
является экраном экономической статистики и не смешивает технические состояния
`UNVERIFIED/NEW/DIRTY/FAILED/VERIFIED` с экономическими alerts
`STOCKOUT/OVERSTOCK/LOW_MARGIN/...`.

```text
ФОЛІО / MS SQL
    -> Java snapshot (чтение)
    -> MariaDB fingerprints и состояния SKU
    -> WordPress выбирает товары для обработки
    -> Java native-range/native-full выполняет штатный перерасчёт
```

Браузер не обращается к MS SQL или MariaDB напрямую. WordPress читает MariaDB
server-side, применяет пагинацию и вызывает административные Java endpoints.

## 2. Когда SKU считается пересчитанным

SKU подтверждённо пересчитан только когда:

1. вызван реальный apply, а не preview;
2. safe-процедура завершилась без диагностической проблемы;
3. Java проверила защищённые остатки, движения и транзакционную границу;
4. MSSQL-транзакция завершилась `COMMIT`;
5. после commit Java записала итоговый `applied_digest` в MariaDB;
6. `applied_digest` совпадает с независимо наблюдаемым `observed_digest`.

```text
verification_state = VERIFIED
observed_digest = applied_digest
last_error IS NULL
```

`priceChanged=false` — успешный результат: процедура подтвердила уже правильную
цену.

## 3. Жизненный цикл SKU

| Событие | Состояние |
|---|---|
| Первый baseline склада | `UNVERIFIED` |
| SKU добавлен после baseline | `NEW` |
| Изменилась карточка, движение или правило цены | `DIRTY` |
| Preview | состояние не меняется |
| Успешный apply и совпавший fingerprint | `VERIFIED` |
| Диагностическая проблема во время apply | `FAILED` |
| Карточка исчезла из ФОЛІО | `REMOVED` |

Повторный snapshot без apply не делает товар `VERIFIED`. После изменения уже
проверенного товара следующий snapshot переводит его в `DIRTY`. `FAILED` —
липкое состояние до исправления исходных документов и успешного apply.

## 4. Правила безопасности

- `snapshot/refresh` читает ФОЛІО и не меняет документы или цены.
- Точечный `/recalculate` с `previewOnly=true` является read-only анализом.
- `native-full` и `native-range`, включая preview, реально вызывают safe-копию
  штатной процедуры. Preview делает rollback, но временно выполняет DML и
  блокирует строки.
- Native preview/apply запускаются только без создания, сохранения и исправления
  документов менеджерами.
- Все операции учётных цен используют один общий слот; склады и режимы нельзя
  запускать параллельно.
- При HTTP `409` новый POST не повторять, а читать status.
- При `FAILED_PARTIAL` или `OUTCOME_UNKNOWN` автоматический повтор запрещён.
- Нельзя исправлять ошибку прямой записью `SCL_ARTC.UCHET_CENA`: исправляется
  исходный документ, затем выполняется штатный перерасчёт.

## 5. Endpoints

### 5.1. Снимок склада

```http
POST /admin/folio/accounting-prices/snapshot/refresh
```

```json
{
  "warehouseId": 5,
  "horizonMonths": 24
}
```

```http
GET /admin/folio/accounting-prices/snapshot/status
```

Опрос каждые 3–5 секунд до `running=false`. Успех:

```text
status = ACTIVE
phase = COMPLETED
```

Склады строятся последовательно.

### 5.2. Обычная проверка одного SKU

```http
POST /admin/folio/accounting-prices/recalculate
```

```json
{
  "sku": "KR-84127",
  "warehouseId": 5,
  "previewOnly": true
}
```

Этот preview ничего не записывает. Apply использует `i_uchet_add(mode=2)` и
восстанавливает итог карточки из сохранённых `SUM_UCHET/SUM_UCVAL`. Он не
заменяет полный штатный алгоритм после исправления старого прихода, налога,
валютного курса или исходной суммы документа.

### 5.3. Штатный перерасчёт одного SKU, диапазона или списка

```http
POST /admin/folio/accounting-prices/recalculate/native-range
```

Один SKU:

```json
{
  "warehouseId": 5,
  "fromSku": "KR-84127",
  "toSku": "KR-84127",
  "previewOnly": false,
  "confirmApply": true
}
```

Диапазон:

```json
{
  "warehouseId": 5,
  "fromSku": "KR-84127",
  "toSku": "KR-84999",
  "previewOnly": false,
  "confirmApply": true
}
```

Явный список:

```json
{
  "warehouseId": 5,
  "skus": ["KR-84127", "СТИ-741449R", "ТП-0001"],
  "previewOnly": false,
  "confirmApply": true
}
```

Передаётся либо `skus[]`, либо одновременно `fromSku` и `toSku`. Максимум 500
SKU. Для исправленных несмежных товаров предпочтителен `skus[]`.

Preview:

```json
{
  "warehouseId": 5,
  "skus": ["KR-84127"],
  "previewOnly": true,
  "confirmApply": false
}
```

Статус:

```http
GET /admin/folio/accounting-prices/recalculate/native-range/status
```

Apply сначала выполняет rollback-preflight всего набора, затем второй проход с
отдельным `COMMIT` каждого чистого SKU. Проблемный SKU откатывается, получает
warning и `FAILED`; остальные продолжаются.

### 5.4. Полный склад

```http
POST /admin/folio/accounting-prices/recalculate/native-full
```

Preview:

```json
{
  "warehouseId": 5,
  "previewOnly": true,
  "confirmApply": false
}
```

Apply:

```json
{
  "warehouseId": 5,
  "previewOnly": false,
  "confirmApply": true
}
```

```http
GET /admin/folio/accounting-prices/recalculate/native-full/status
```

`native-full` нужен для первой полной верификации или ручного аудита. После
baseline регулярная работа выполняется через `native-range` со списком
изменившихся SKU.

## 6. Статусы native job

| Статус | Значение | Действие фронта |
|---|---|---|
| `QUEUED` | задача принята | начать polling |
| `RUNNING` | выполняется | блокировать повторный POST |
| `PREVIEW_READY` | preview без проблем | разрешить apply |
| `PREVIEW_READY_WITH_WARNINGS` | часть SKU проблемная | показать warnings; apply разрешён с пропуском |
| `COMPLETED` | apply завершён | запустить snapshot склада |
| `COMPLETED_WITH_WARNINGS` | чистые сохранены, проблемные пропущены | snapshot и список ошибок оператору |
| `FAILED` | ошибка до первого commit | показать error/failedChunk/reqId |
| `FAILED_PARTIAL` | часть SKU могла быть сохранена | запретить авто-повтор |
| `OUTCOME_UNKNOWN` | commit/rollback не доказан | запретить авто-повтор, независимая проверка |
| `BUSY` | общий слот занят | показать текущий status |

В GET `accepted=false` нормально: поле относится к исходному POST. Polling
завершается по `running=false`.

Показывать: `jobId`, `phase`, `startedAt`, `completedAt`, `procedureCalls`,
`preflightChunks`, `committedChunks`, `progressUnits`, `totalUnits`,
`processedSku`, `currentUnits`, `procedureCurrentUnits`,
`procedureTotalUnits`, `progressPercent`, `currentArt`, `nextArt`,
`lastCommittedArt`, `checkpointArt`, `warningCount`, `warningsTruncated`,
`warnings[]`, `failedChunk`, `errorCode`, `error`.

Для `native-range` прогресс кампании — это `progressUnits/totalUnits`, где оба
значения считаются Java в SKU. `processedSku` дублирует числитель в явном виде.
`procedureCurrentUnits/procedureTotalUnits` — сырые legacy work units последнего
вызова и не используются как размер кампании. Значение
`procedureTotalUnits=0` допустимо, если `totalUnits>0`: safe-процедура работает
по одному SKU и не знает размер выбранного WordPress batch.

`NATIVE_RANGE_TOTAL_UNKNOWN` означает, что Java не смогла сформировать
непустую выборку до запуска. `NATIVE_RANGE_CONTRACT_INVALID` означает настоящее
нарушение OUT/cursor-контракта. Нельзя показывать ни один из этих кодов только
из-за `procedureTotalUnits=0` при известном Java total.

## 7. Экран оператора

### 7.1. Сводка

Отдельные счётчики: `UNVERIFIED`, `NEW`, `DIRTY`, `FAILED`, `VERIFIED`,
`REMOVED`. Фильтры: склад, SKU/название, состояние, дата наблюдения, дата apply,
наличие `last_error`.

### 7.2. Действия

- «Обновить снимок» — `snapshot/refresh`.
- «Проверить выбранные» — `native-range`, `previewOnly=true`.
- «Пересчитать выбранные» — `native-range`, `previewOnly=false`.
- «Полный склад» — `native-full`, только право обслуживания.
- «Скопировать диагностику» — полный warning JSON без реквизитов подключения.

Перед apply оператор подтверждает: менеджеры не работают в ФОЛІО, есть
резервная копия, выбран правильный склад и проверено количество SKU.

## 8. Отрицательный хронологический остаток

### 8.1. Смысл

Это не обязательно текущий отрицательный остаток. В истории товара в некоторый
момент расход оказался больше накопленного к этой дате количества. Текущий
фактический остаток может быть положительным. При одинаковой дате штатный
алгоритм обрабатывает приходы раньше расходов.

### 8.2. Карточка ошибки

Для `NEGATIVE_CHRONOLOGICAL_STOCK` показать:

- SKU и склад;
- `folioProblemDate`;
- `initialQuantity`;
- `movementPosition` и `movementCount`;
- `quantityBefore`, количество операции, `quantityAfter`;
- `shortageQuantity`;
- текущие `physicalQuantity`, `availableQuantity`, `accountingQuantity`,
  `accountingPrice`;
- весь `details.operation`.

Из `details.operation` показать:

- `documentType` и `kind`;
- видимый `documentNumber`;
- внутренний `documentId`;
- `documentDate`;
- `quantity`;
- `warehouseId`;
- `recno` как технический идентификатор движения.

Текст оператору:

```text
После этой операции хронологический остаток стал отрицательным.
Проверьте указанный документ и более ранние приходы товара.
Не исправляйте учётную цену вручную.
```

### 8.3. Что проверять

1. Был ли фактический приход до проблемного расхода.
2. Не записан ли приход ошибочной более поздней датой.
3. Правильны ли склад, артикул и количество прихода.
4. Не завышено ли количество расхода.
5. Не проведён ли документ по неправильному складу.
6. Не удалён ли старый приход.
7. Не оформлен ли возврат неправильным типом.

Корректируется только фактически неверный документ. Нельзя произвольно менять
дату правильного документа ради прохождения перерасчёта.

После исправления: snapshot, native-range preview одного SKU, native-range
apply, новый snapshot, проверка `VERIFIED` и пустого `last_error`.

## 9. Нулевой знаменатель

Для `ZERO_ACCOUNTING_DENOMINATOR`/`ACCOUNTING_PRICE_DIVIDE_BY_ZERO` показать:
SKU, склад, `problemRecno`/`recno`, `operationDate`, `formula`, `numerator`,
`denominator`, `quantityBefore`, `movementQuantity`, текущую сумму и цену.

Оператор проверяет приходный документ, количество, цену, сумму, налог, валютный
курс и движение при нулевой базе расчёта. Исправляется исходный документ, после
чего повторяется native-range одного SKU.

Новая карточка без движений, с нулевым остатком и нулевой учётной ценой —
нормальное состояние и сама по себе не является ошибкой.

## 10. Первая верификация 5–6 складов

Первичная верификация может не поместиться в одну ночь. Склады не запускаются
параллельно; кампания продолжается несколько ночей.

### 10.1. Подготовка

1. Построить свежий snapshot складов последовательно.
2. Зафиксировать количество состояний.
3. Начать с наиболее важного склада.
4. Первый batch — 100 SKU, чтобы измерить время.
5. После стабильного результата увеличить batch до 250–500 SKU.

WordPress выбирает server-side:

```sql
SELECT sku
FROM folio_product_snapshot_item
WHERE source_database = :sourceDatabase
  AND warehouse_id = :warehouseId
  AND present_in_folio = 1
  AND verification_state IN ('UNVERIFIED', 'NEW', 'DIRTY')
ORDER BY sku
LIMIT :batchSize;
```

`FAILED` автоматически не включать: требуется исправление оператором.
`VERIFIED` не включать.

### 10.2. Ночной цикл

1. Убедиться, что предыдущая job не `running`.
2. Проверить окно обслуживания и отсутствие пользователей ФОЛІО.
3. Получить batch из MariaDB.
4. Передать `skus[]` в `native-range` apply.
5. Poll status до `running=false`.
6. Для `COMPLETED/COMPLETED_WITH_WARNINGS` сохранить журнал.
7. При наличии времени отправить следующий batch.
8. Перед началом рабочего времени новые batch не принимать.
9. После ночи построить snapshot затронутых складов.
10. Следующей ночью продолжить оставшиеся состояния.

В API нет безопасного cancel endpoint. Размер batch выбирается так, чтобы job
завершилась до работы менеджеров.

```text
safeBatch = min(500,
                floor(remainingWindowSeconds * 0.70 / measuredSecondsPerSku))
```

30% окна резервируется на колебание нагрузки, snapshot и диагностику. Если
`safeBatch < 1`, новый batch не запускать.

После ночной обработки требуется snapshot: если процедура изменила данные,
новый `applied_digest` может отличаться от старого `observed_digest`. Snapshot
независимо перечитывает ФОЛІО и завершает перевод в `VERIFIED`.

## 11. Регулярный cron

После первичной кампании cron:

1. последовательно строит snapshot складов;
2. выбирает только `NEW` и `DIRTY`;
3. разбивает их на `skus[]` batches;
4. последовательно запускает `native-range` apply;
5. не повторяет `FAILED` без решения оператора;
6. повторно строит snapshot затронутых складов;
7. отправляет отчёт: verified, failed, warnings, duration.

`UNVERIFIED` включается до завершения первичной кампании. После неё его появление
требует проверки.

Важно: API пока не имеет `onlyDirty`. WordPress выбирает SKU по MariaDB.
`VERIFIED` будет пересчитан снова, если фронт явно передаст его в native-range.

## 12. Реакция cron на ошибки

| Результат | Действие |
|---|---|
| `COMPLETED` | snapshot, следующий batch |
| `COMPLETED_WITH_WARNINGS` | snapshot, сохранить warnings, следующий batch |
| `FAILED` | остановить текущий склад, уведомить оператора |
| `FAILED_PARTIAL` | остановить всю кампанию, ручная сверка |
| `OUTCOME_UNKNOWN` | остановить всё, независимая проверка MSSQL/MariaDB |
| HTTP `409 BUSY` | не создавать дубль, читать status |
| HTTP `403` | ошибка конфигурации, не повторять |
| HTTP `503`/timeout | сначала проверить status, apply автоматически не повторять |

Сохранять `jobId`, склад, SKU, code, message, полный `details` и время. При
`warningsTruncated=true` HTTP содержит не весь список; отчёт помечается
неполным, полная диагностика берётся из серверных логов.

## 13. Feature flags

```properties
LAVKA_FOLIO_ACCOUNTING_PRICE_API_ENABLED=true
LAVKA_FOLIO_ACCOUNTING_PRICE_APPLY_ENABLED=true
LAVKA_FOLIO_ACCOUNTING_PRICE_NATIVE_FULL_ENABLED=true
LAVKA_FOLIO_ACCOUNTING_PRICE_NATIVE_FULL_APPLY_ENABLED=true
LAVKA_FOLIO_ACCOUNTING_PRICE_NATIVE_FULL_ALLOWED_DATABASES=Paint_Ua
LAVKA_FOLIO_PRODUCT_SNAPSHOT_ENABLED=true
LAVKA_FOLIO_PRODUCT_SNAPSHOT_HORIZON_MONTHS=24
```

Фронтенд не меняет flags. После изменения окружения Java-контейнер
пересоздаётся.

## 14. Приёмка фронта

1. Preview визуально отделён от apply.
2. Apply требует подтверждения оператора.
3. При `running=true` повторный POST заблокирован.
4. Polling переживает перезагрузку страницы.
5. `COMPLETED_WITH_WARNINGS` показывается как успешная обработка с пропусками.
6. `FAILED_PARTIAL`/`OUTCOME_UNKNOWN` не имеют автоматического повтора.
7. Отрицательный остаток показывает точный документ и количества до/после.
8. После apply запускается snapshot.
9. Cron не передаёт `VERIFIED` и не повторяет `FAILED` без исправления.
10. Запросы доступны только через защищённый VPN/internal network.
