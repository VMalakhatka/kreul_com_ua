# Постоянная история «Прибыли Лавки»

Контракт разработки 2026-09-09. Миграция V15 выполняется только в application
MariaDB/wpDataSource, не в legacy ФОЛИО. Формулы и фильтры прибыли не меняются.

## Методы

Базовый путь `/admin/folio/profit-report/saved`.

- `GET ?fromMonth=2025-07&toMonth=2025-11` — включительный диапазон до24месяцев.
  Только сохранённые данные; отсутствующие месяцы не рассчитываются.
- `GET /{month}` — опубликованная ревизия. `?revisionId=...` выбирает конкретную
  ревизию этого месяца, включая предварительную/ошибочную/незавершённую.
- `GET /{month}/revisions?limit=20&beforeRevisionId=...` — metadata по убыванию ID,
  limit1..100, для следующей страницы передавать последний ID.
- `POST /{month}/calculate` — явный расчёт **одного** месяца с полным auditDTO.
  Синхронный запрос; frontend диапазон обрабатывает последовательными командами.

Ответ истории: `{ok, month, revisions, hasMore, nextBeforeRevisionId}`.
Элементы `revisions` содержат метаданные ревизии без полного `report`;
для подробностей используется GET конкретного месяца с `revisionId`.

```json
{
  "requestId": "b7a3b49e-8c04-4a6b-b250-6b9e44a76cf0",
  "odesaTaxShare": 0.4285714286,
  "rubToUahRate": 0.41,
  "odesaAdditionalSalary": 0,
  "kyivAdditionalSalary": 0,
  "kyivStockWarehouseIds": [1,7,12],
  "odesaStockWarehouseIds": [5]
}
```

Опциональные `odesaMasterClassIncome/Return` остаются legacy ignored параметрами.
Отсутствие и явный0 сохраняются различными. Defaults применяет прежний сервис
один раз для каждого месяца, затем сохраняются `report.inputs`. В частности,
default5000 Одессы — **на каждый месяц**, не один раз на диапазон.

## Ревизия и публикация

Wrapper: `ok`, `status`, `sourceDatabase`, `month`, `revisionId`, `requestId`,
`publishedRevisionId`, `latestRevisionId`, `latestStatus`, `published`,
`auditComplete`, `createdAt`, `completedAt`, `request` (исходные параметры),
`report` (полный неизменённый FolioProfitReportResponse или null), `errorCode`.
Статусы: `RUNNING`, `FAILED`, `COMPLETED`, `PROVISIONAL`, `MISSING`.
ok=true означает сохранённый результат, но не его полную финансовую проверку.

Первый результат, включая предварительный, доступен как опубликованный.
PROVISIONAL не заменяет ранее опубликованный отчёт. COMPLETED заменяет pointer
только при более новом revisionId. Ошибки остаются в истории и не удаляют
прежний отчёт. `latestRevisionId/latestStatus` показывают новый результат даже
когда опубликованная ревизия осталась прежней. Можно открыть его по revisionId.
Кнопка ручного продвижения предварительного результата в v1 отсутствует.

COMPLETED требует report.complete=true и полного сохранённого аудита по
имеющимся truncationflags. PROVISIONAL включает неполные разделы, бизнес-
предупреждения, требующие проверки, или ограниченный audit. Сохраняются **все**
имеющиеся diagnostics/warnings/sections/flags, не только сводка. Если audit
ограничен500 документов, сохранение не делает его полным. Исходные legacy GET
summary/audit пока остаются live и не сохраняют результат автоматически.

POST обязательно содержит UUID requestId. Он уникален в sourceDatabase.
Повтор **того же** запроса возвращает прежнюю ревизию без обращения к ФОЛИО;
тот же UUID с иными параметрами/месяцем — конфликт. Повтор RUNNING не начинает
второй расчёт. RUNNING после потери процесса может остаться в истории: это
сохранённое состояние запроса, не доказательство активности Java. Нет
автоматического повторения/восстановления расчёта после рестарта.

При потере HTTP frontend читает revisions/GET по ID и не выдаёт новый UUID
автоматически. Команда остановки диапазона не прерывает текущий POST — только
запрещает отправку следующих месяцев. Таймаут не доказывает отсутствие commit
application DB. Если DB недоступна, нельзя объявлять результат сохранённым.

## Диапазон

Ответ: `ok`, `fromMonth`, `toMonth`, `sourceDatabase`, `complete`, `missingMonths`,
`months[]`, `totals[]`, `warnings[]`.
months — краткие wrappers опубликованных ревизий: month/revisionId/status,
latestRevisionId/latestStatus, calculatedAt, ruleVersion, auditComplete,
inputs/cities/inventory (без полного audit массива). Несохранённые месяцы имеют
statusMISSING и перечислены в missingMonths.

totals по KYIV/ODESA: `city`, `baseGrossProfit`, `manualGrossAdjustments`,
`grossProfit`, `operatingExpenses`, `profit`, `openingAccountingValue`,
`closingAccountingValue`, `accountingValueChange`, `complete`.
Деньги — decimal строки/null, валюта потоков отчёта UAH. Выручка не подменяется
валовой прибылью: прежний DTO не содержит sales revenue, её новый API не выдумывает.

Потоки складываются только если каждый месяц содержит это поле; при missing/null
общий показатель null, не сумма доступных месяцев. Известные предварительные
суммы допускаются с complete=false. Остатки: начало **первого запрошенного** и
конец **последнего запрошенного** месяца, никогда не сумма остатков. Изменение
остатков null при неполных границах/разном составе складов; состав за весь
диапазон должен совпадать. При разных warehouse scopes сумма валовой прибыли
и зависимая прибыль не сопоставимы — null с предупреждением.
Курс и налоговая доля не усредняются: каждый месяц сохраняет свои inputs.

## Хранение и ограничения

`folio_profit_report_revision`: immutable terminal payload LONGTEXT JSON,
raw request JSON/hash, source/month, статус, даты, auditComplete, errorCode.
`folio_profit_report_month`: атомарный указатель опубликованной и последней
запрошенной ревизии, ключ sourceDatabase×YYYY-MM. Поздно завершившаяся старая
команда не откатывает опубликованный указатель назад. Расчёт выполняется вне
MariaDB-транзакции, только финальное сохранение+publication — одна транзакция.

Namespace базы берётся из настроенного JDBC URL без подключения к ФОЛИО.
При неопределённом имени источника запрос отклоняется, а не смешивается с Paint_Ua.
JSON содержит закрытые финансовые документы: только защищённый admin proxy,
резервное копирование appDB, не логировать payload и не публиковать raw exports.
GET истории никогда не вызывает FolioProfitReportService или DAO ФОЛИО.
Для включения нужны V15 и Java deploy; в рамках разработки миграция на prod
не применяется, deploy/commit/push отдельно по команде пользователя.
